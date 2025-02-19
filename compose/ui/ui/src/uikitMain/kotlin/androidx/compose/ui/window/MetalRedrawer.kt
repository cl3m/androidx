/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.window

import androidx.compose.ui.util.fastForEach
import kotlinx.cinterop.*
import org.jetbrains.skia.*
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSRunLoop
import platform.Foundation.NSSelectorFromString
import platform.Metal.MTLCommandBufferProtocol
import platform.QuartzCore.*
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.*
import kotlin.math.roundToInt
import platform.Foundation.NSThread

private class DisplayLinkConditions(
    val setPausedCallback: (Boolean) -> Unit
) {
    /**
     * see [MetalRedrawer.needsProactiveDisplayLink]
     */
    var needsToBeProactive: Boolean = false
        set(value) {
            field = value

            update()
        }

    /**
     * Indicates that scene is invalidated and next display link callback will draw
     */
    var needsRedrawOnNextVsync: Boolean = false
        set(value) {
            field = value

            update()
        }

    /**
     * Indicates that application is running foreground now
     */
    var isApplicationActive: Boolean = false
        set(value) {
            field = value

            update()
        }

    private fun update() {
        val isUnpaused = isApplicationActive && (needsToBeProactive || needsRedrawOnNextVsync)
        setPausedCallback(!isUnpaused)
    }
}

private class ApplicationStateListener(
    /**
     * Callback which will be called with `true` when the app becomes active, and `false` when the app goes background
     */
    private val callback: (Boolean) -> Unit
) : NSObject() {
    init {
        val notificationCenter = NSNotificationCenter.defaultCenter

        notificationCenter.addObserver(
            this,
            NSSelectorFromString(::applicationWillEnterForeground.name),
            UIApplicationWillEnterForegroundNotification,
            null
        )

        notificationCenter.addObserver(
            this,
            NSSelectorFromString(::applicationDidEnterBackground.name),
            UIApplicationDidEnterBackgroundNotification,
            null
        )
    }

    @ObjCAction
    fun applicationWillEnterForeground() {
        callback(true)
    }

    @ObjCAction
    fun applicationDidEnterBackground() {
        callback(false)
    }

    /**
     * Deregister from [NSNotificationCenter]
     */
    fun dispose() {
        val notificationCenter = NSNotificationCenter.defaultCenter

        notificationCenter.removeObserver(this, UIApplicationWillEnterForegroundNotification, null)
        notificationCenter.removeObserver(this, UIApplicationDidEnterBackgroundNotification, null)
    }
}

private enum class DrawReason {
    DISPLAY_LINK_CALLBACK, SYNCHRONOUS_DRAW_REQUEST
}

internal class MetalRedrawer(
    private val metalLayer: CAMetalLayer,
    private val drawCallback: (Surface) -> Unit,
    private val retrieveCATransactionCommands: () -> List<() -> Unit>,

    // Used for tests, access to NSRunLoop crashes in test environment
    addDisplayLinkToRunLoop: ((CADisplayLink) -> Unit)? = null,
    private val disposeCallback: (MetalRedrawer) -> Unit = { }
) {
    // Workaround for KN compiler bug
    // Type mismatch: inferred type is objcnames.protocols.MTLDeviceProtocol but platform.Metal.MTLDeviceProtocol was expected
    @Suppress("USELESS_CAST")
    private val device = metalLayer.device as platform.Metal.MTLDeviceProtocol?
        ?: throw IllegalStateException("CAMetalLayer.device can not be null")
    private val queue = device.newCommandQueue() ?: throw IllegalStateException("Couldn't create Metal command queue")
    private val context = DirectContext.makeMetal(device.objcPtr(), queue.objcPtr())
    private val inflightCommandBuffers = mutableListOf<MTLCommandBufferProtocol>()

    // Semaphore for preventing command buffers count more than swapchain size to be scheduled/executed at the same time
    private val inflightSemaphore = dispatch_semaphore_create(metalLayer.maximumDrawableCount.toLong())

    var isForcedToPresentWithTransactionEveryFrame = false

    var maximumFramesPerSecond: NSInteger
        get() = caDisplayLink?.preferredFramesPerSecond ?: 0
        set(value) {
            caDisplayLink?.preferredFramesPerSecond = value
        }

    /**
     * Needs scheduling displayLink for forcing UITouch events to come at the fastest possible cadence.
     * Otherwise, touch events can come at rate lower than actual display refresh rate.
     */
    var needsProactiveDisplayLink: Boolean
        get() = displayLinkConditions.needsToBeProactive
        set(value) {
            displayLinkConditions.needsToBeProactive = value
        }

    private var caDisplayLink: CADisplayLink? = CADisplayLink.displayLinkWithTarget(
        target = DisplayLinkProxy {
            this.handleDisplayLinkTick()
        },
        selector = NSSelectorFromString(DisplayLinkProxy::handleDisplayLinkTick.name)
    )

    private val displayLinkConditions = DisplayLinkConditions { paused ->
        caDisplayLink?.setPaused(paused)
    }

    private val applicationStateListener = ApplicationStateListener { isApplicationActive ->
        displayLinkConditions.isApplicationActive = isApplicationActive

        if (!isApplicationActive) {
            // If application goes background, synchronously schedule all inflightCommandBuffers, as per
            // https://developer.apple.com/documentation/metal/gpu_devices_and_work_submission/preparing_your_metal_app_to_run_in_the_background?language=objc
            inflightCommandBuffers.forEach {
                // Will immediately return for MTLCommandBuffer's which are not in `Commited` status
                it.waitUntilScheduled()
            }
        }
    }

    init {
        val caDisplayLink = caDisplayLink ?: throw IllegalStateException("caDisplayLink is null during redrawer init")

        // UIApplication can be in UIApplicationStateInactive state (during app launch before it gives control back to run loop)
        // and won't receive UIApplicationWillEnterForegroundNotification
        // so we compare the state with UIApplicationStateBackground instead of UIApplicationStateActive
        displayLinkConditions.isApplicationActive = UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground

        if (addDisplayLinkToRunLoop == null) {
            caDisplayLink.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoop.mainRunLoop.currentMode)
        } else {
            addDisplayLinkToRunLoop.invoke(caDisplayLink)
        }
    }

    fun dispose() {
        check(caDisplayLink != null) { "MetalRedrawer.dispose() was called more than once" }

        disposeCallback(this)

        applicationStateListener.dispose()

        caDisplayLink?.invalidate()
        caDisplayLink = null

        context.flush()
        context.close()
    }

    /**
     * Marks current state as dirty and unpauses display link if needed and enables draw dispatch operation on
     * next vsync
     */
    fun needRedraw() {
        displayLinkConditions.needsRedrawOnNextVsync = true
    }

    private fun handleDisplayLinkTick() {
        if (displayLinkConditions.needsRedrawOnNextVsync) {
            displayLinkConditions.needsRedrawOnNextVsync = false

            draw(DrawReason.DISPLAY_LINK_CALLBACK)
        }
    }

    /**
     * Immediately dispatch draw and block the thread until it's finished and presented on the screen.
     */
    fun drawSynchronously() {
        draw(DrawReason.SYNCHRONOUS_DRAW_REQUEST)
    }

    private fun draw(reason: DrawReason) {
        check(NSThread.isMainThread)

        if (caDisplayLink == null) {
            // TODO: anomaly, log
            // Logger.warn { "caDisplayLink callback called after it was invalidated " }
            return
        }

        autoreleasepool {
            val (width, height) = metalLayer.drawableSize.useContents {
                width.roundToInt() to height.roundToInt()
            }

            if (width <= 0 || height <= 0) {
                return@autoreleasepool
            }

            dispatch_semaphore_wait(inflightSemaphore, DISPATCH_TIME_FOREVER)

            val metalDrawable = metalLayer.nextDrawable()

            if (metalDrawable == null) {
                // TODO: anomaly, log
                // Logger.warn { "'metalLayer.nextDrawable()' returned null. 'metalLayer.allowsNextDrawableTimeout' should be set to false. Skipping the frame." }
                dispatch_semaphore_signal(inflightSemaphore)
                return@autoreleasepool
            }

            val renderTarget = BackendRenderTarget.makeMetal(width, height, metalDrawable.texture.objcPtr())

            val surface = Surface.makeFromBackendRenderTarget(
                context,
                renderTarget,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN)
            )

            if (surface == null) {
                // TODO: anomaly, log
                // Logger.warn { "'Surface.makeFromBackendRenderTarget' returned null. Skipping the frame." }
                renderTarget.close()
                // TODO: manually release metalDrawable when K/N API arrives
                dispatch_semaphore_signal(inflightSemaphore)
                return@autoreleasepool
            }

            surface.canvas.clear(Color.WHITE)
            drawCallback(surface)
            surface.flushAndSubmit()

            val caTransactionCommands = retrieveCATransactionCommands()
            val presentsWithTransaction = isForcedToPresentWithTransactionEveryFrame || caTransactionCommands.isNotEmpty()

            metalLayer.presentsWithTransaction = presentsWithTransaction

            val commandBuffer = queue.commandBuffer()!!
            commandBuffer.label = "Present"

            if (!presentsWithTransaction) {
                // If there are no pending changes in UIKit interop, present the drawable ASAP
                commandBuffer.presentDrawable(metalDrawable)
            }

            commandBuffer.addCompletedHandler {
                // Signal work finish, allow a new command buffer to be scheduled
                dispatch_semaphore_signal(inflightSemaphore)
            }
            commandBuffer.commit()

            if (presentsWithTransaction) {
                // If there are pending changes in UIKit interop, [waitUntilScheduled](https://developer.apple.com/documentation/metal/mtlcommandbuffer/1443036-waituntilscheduled) is called
                // to ensure that transaction is available
                commandBuffer.waitUntilScheduled()
                metalDrawable.present()
                caTransactionCommands.fastForEach { it.invoke() }
                CATransaction.flush()
            }

            surface.close()
            renderTarget.close()
            // TODO manually release metalDrawable when K/N API arrives

            // Track current inflight command buffers to synchronously wait for their schedule in case app goes background
            if (inflightCommandBuffers.size == metalLayer.maximumDrawableCount.toInt()) {
                inflightCommandBuffers.removeAt(0)
            }

            inflightCommandBuffers.add(commandBuffer)

            if (reason == DrawReason.SYNCHRONOUS_DRAW_REQUEST) {
                commandBuffer.waitUntilCompleted()
            }
        }
    }
}

private class DisplayLinkProxy(
    private val callback: () -> Unit
) : NSObject() {
    @ObjCAction
    fun handleDisplayLinkTick() {
        callback()
    }
}
