/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.internal.keyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import com.google.common.truth.Truth.assertThat
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DesktopMenuTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun menu_positioning_vertical_underAnchor() {
        val windowSize = IntSize(200, 200)
        val anchorBounds = IntRect(
            offset = IntOffset(10, 100),
            size = IntSize(80, 20)
        )
        val popupSize = IntSize(80, 50)

        val position = DropdownMenuPositionProvider(
            DpOffset.Zero,
            Density(1f)
        ).calculatePosition(
            anchorBounds,
            windowSize,
            LayoutDirection.Ltr,
            popupSize
        )

        assertThat(position).isEqualTo(
            IntOffset(
                x = anchorBounds.left,
                y = anchorBounds.top - popupSize.height
            )
        )
    }

    // (RTL) Anchor right is beyond the right of the window, so align popup to the window right
    @Test
    fun menu_positioning_rtl_windowRight_belowAnchor() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize().testTag("background")) {
                    Box(Modifier.offset(x = (-10).dp).size(50.dp)) {
                        DropdownMenu(true, onDismissRequest = {}) {
                            Box(Modifier.size(50.dp).testTag("box"))
                        }
                    }
                }
            }
        }
        val windowSize = rule.onNodeWithTag("background").getBoundsInRoot().size
        rule.onNodeWithTag("box")
            .assertLeftPositionInRootIsEqualTo(windowSize.width - 50.dp)
    }

    @Test
    fun `pressing ESC button invokes onDismissRequest`() {
        var dismissCount = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                DropdownMenu(true, onDismissRequest = {
                    dismissCount++
                }, modifier = Modifier.testTag("dropDownMenu")) {
                    DropdownMenuItem(onClick = {}, text = { Text("item1") })
                }
            }
        }

        rule.onNodeWithTag("dropDownMenu")
            .performKeyPress(keyEvent(Key.Escape, KeyEventType.KeyDown))

        rule.runOnIdle {
            Assert.assertEquals(1, dismissCount)
        }

        rule.onNodeWithTag("dropDownMenu")
            .performKeyPress(keyEvent(Key.Escape, KeyEventType.KeyUp))

        rule.runOnIdle {
            Assert.assertEquals(1, dismissCount)
        }
    }

    @Test
    fun `navigate DropDownMenu using arrows`() {
        var item1Clicked = 0
        var item2Clicked = 0
        var item3Clicked = 0

        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                DropdownMenu(true, onDismissRequest = {},
                    modifier = Modifier.testTag("dropDownMenu")) {
                    DropdownMenuItem(
                        text = { Text("item1") },
                        onClick = { item1Clicked++ }
                    )
                    DropdownMenuItem(
                        text = { Text("item2") },
                        onClick = { item2Clicked++ }
                    )
                    DropdownMenuItem(
                        text = { Text("item3") },
                        onClick = { item3Clicked++ }
                    )
                }
            }
        }

        fun performKeyDownAndUp(key: Key) {
            rule.onNodeWithTag("dropDownMenu").apply {
                performKeyPress(keyEvent(key, KeyEventType.KeyDown))
                performKeyPress(keyEvent(key, KeyEventType.KeyUp))
            }
        }

        fun assertClicksCount(i1: Int, i2: Int, i3: Int) {
            rule.runOnIdle {
                assertThat(item1Clicked).isEqualTo(i1)
                assertThat(item2Clicked).isEqualTo(i2)
                assertThat(item3Clicked).isEqualTo(i3)
            }
        }

        performKeyDownAndUp(Key.DirectionDown)
        performKeyDownAndUp(Key.Enter)
        assertClicksCount(1, 0, 0)

        performKeyDownAndUp(Key.DirectionUp)
        performKeyDownAndUp(Key.Enter)
        assertClicksCount(1, 0, 1)

        performKeyDownAndUp(Key.DirectionUp)
        performKeyDownAndUp(Key.Enter)
        assertClicksCount(1, 1, 1)

        performKeyDownAndUp(Key.DirectionDown)
        performKeyDownAndUp(Key.Enter)
        assertClicksCount(1, 1, 2)

        performKeyDownAndUp(Key.DirectionDown)
        performKeyDownAndUp(Key.Enter)
        assertClicksCount(2, 1, 2)

        performKeyDownAndUp(Key.DirectionDown)
        performKeyDownAndUp(Key.Enter)
        assertClicksCount(2, 2, 2)
    }
}
