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

package androidx.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.work.ExistingWorkPolicy.APPEND
import androidx.work.ExistingWorkPolicy.KEEP
import androidx.work.impl.Processor
import androidx.work.impl.Scheduler
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkLauncherImpl
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.background.greedy.GreedyScheduler
import androidx.work.impl.constraints.trackers.Trackers
import androidx.work.impl.testutils.TestConstraintTracker
import androidx.work.impl.testutils.TrackingWorkerFactory
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import androidx.work.worker.LatchWorker
import androidx.work.worker.TestWorker
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

@SmallTest
class WorkInfoFlowsTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val workerFactory = TrackingWorkerFactory()
    val configuration = Configuration.Builder().setWorkerFactory(workerFactory).build()
    val executor = Executors.newSingleThreadExecutor()
    val taskExecutor = WorkManagerTaskExecutor(executor)
    val fakeChargingTracker = TestConstraintTracker(false, context, taskExecutor)
    val trackers = Trackers(
        context = context,
        taskExecutor = taskExecutor,
        batteryChargingTracker = fakeChargingTracker
    )
    val db = WorkDatabase.create(context, executor, configuration.clock, true)

    // ugly, ugly hack because of circular dependency:
    // Schedulers need WorkManager, WorkManager needs schedulers
    val schedulers = mutableListOf<Scheduler>()
    val processor = Processor(context, configuration, taskExecutor, db)
    val workManager = WorkManagerImpl(
        context, configuration, taskExecutor, db, schedulers, processor, trackers
    )
    val greedyScheduler = GreedyScheduler(context, configuration, trackers,
        processor, WorkLauncherImpl(processor, taskExecutor))

    init {
        schedulers.add(greedyScheduler)
        WorkManagerImpl.setDelegate(workManager)
    }

    val unrelatedRequest = OneTimeWorkRequest.Builder(TestWorker::class.java)
        .setInitialDelay(1, TimeUnit.DAYS)
        .build()

    @Test
    fun flowById() = runBlocking {
        val request = OneTimeWorkRequest.Builder(LatchWorker::class.java)
            .setConstraints(Constraints(requiresCharging = true))
            .build()
        val tester = launchTester(workManager.getWorkInfoByIdFlow(request.id))
        assertThat(tester.awaitNext()).isNull()
        workManager.enqueue(unrelatedRequest)
        workManager.enqueue(request)
        assertThat(tester.awaitNext().state).isEqualTo(WorkInfo.State.ENQUEUED)
        fakeChargingTracker.state = true

        assertThat(tester.awaitNext().state).isEqualTo(WorkInfo.State.RUNNING)
        val worker = workerFactory.awaitWorker(request.id) as LatchWorker
        worker.mLatch.countDown()
        assertThat(tester.awaitNext().state).isEqualTo(WorkInfo.State.SUCCEEDED)
    }

    @Test
    fun flowByName() = runBlocking<Unit> {
        val request1 = OneTimeWorkRequest.Builder(TestWorker::class.java)
            .setConstraints(Constraints(requiresCharging = true))
            .build()
        val request2 = OneTimeWorkRequest.Builder(TestWorker::class.java)
            .setConstraints(Constraints(requiresCharging = true))
            .build()
        val tester = launchTester(workManager.getWorkInfosForUniqueWorkFlow("name"))
        assertThat(tester.awaitNext()).isEmpty()
        workManager.enqueue(unrelatedRequest)
        workManager.enqueueUniqueWork("name", KEEP, request1)
        val firstList = tester.awaitNext()
        assertThat(firstList.size).isEqualTo(1)
        assertThat(firstList.first().id).isEqualTo(request1.id)
        workManager.enqueueUniqueWork("name", APPEND, request2)
        val secondList = tester.awaitNext()
        assertThat(secondList.size).isEqualTo(2)
        assertThat(secondList.map { it.id }).containsExactly(request1.id, request2.id)
    }

    @Test
    fun flowByQuery() = runBlocking<Unit> {
        val request1 = OneTimeWorkRequest.Builder(TestWorker::class.java)
            .setConstraints(Constraints(requiresCharging = true))
            .build()
        val request2 = OneTimeWorkRequest.Builder(TestWorker::class.java)
            .setConstraints(Constraints(requiresCharging = true))
            .build()
        val query = WorkQuery.fromIds(request1.id, request2.id)
        val tester = launchTester(workManager.getWorkInfosFlow(query))
        assertThat(tester.awaitNext()).isEmpty()
        workManager.enqueue(unrelatedRequest)
        workManager.enqueue(request1)
        val firstList = tester.awaitNext()
        assertThat(firstList.size).isEqualTo(1)
        assertThat(firstList.first().id).isEqualTo(request1.id)
        workManager.enqueue(request2)
        val secondList = tester.awaitNext()
        assertThat(secondList.size).isEqualTo(2)
        assertThat(secondList.map { it.id }).containsExactly(request1.id, request2.id)
    }

    @Test
    fun flowByTag() = runBlocking<Unit> {
        val request1 = OneTimeWorkRequest.Builder(TestWorker::class.java)
            .setConstraints(Constraints(requiresCharging = true))
            .addTag("tag")
            .build()
        val request2 = OneTimeWorkRequest.Builder(TestWorker::class.java)
            .setConstraints(Constraints(requiresCharging = true))
            .addTag("tag")
            .build()
        val tester = launchTester(workManager.getWorkInfosByTagFlow("tag"))

        assertThat(tester.awaitNext()).isEmpty()
        workManager.enqueue(unrelatedRequest)
        workManager.enqueue(request1)
        val firstList = tester.awaitNext()
        assertThat(firstList.size).isEqualTo(1)
        assertThat(firstList.first().id).isEqualTo(request1.id)
        workManager.enqueue(unrelatedRequest)
        workManager.enqueue(request2)
        val secondList = tester.awaitNext()
        assertThat(secondList.size).isEqualTo(2)
        assertThat(secondList.map { it.id }).containsExactly(request1.id, request2.id)
    }
}

private fun <T> CoroutineScope.launchTester(flow: Flow<T>): FlowTester<T> {
    val tester = FlowTester(flow)
    // we don't block parent from completing and simply stop collecting once parent is done
    val forked = Job()
    coroutineContext.job.invokeOnCompletion { forked.cancel() }
    launch(Job()) { tester.launch(this) }
    return tester
}

private class FlowTester<T>(private val flow: Flow<T>) {
    private val channel = Channel<T>(10)

    suspend fun awaitNext(): T {
        val result = try {
            withTimeout(3000L) { channel.receive() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Didn't receive event")
        }
        val next = channel.tryReceive()
        if (next.isSuccess || next.isClosed)
            throw AssertionError(
                "Two events received instead of one;\n" +
                    "first: $result;\nsecond: ${next.getOrNull()}"
            )
        return result
    }

    fun launch(scope: CoroutineScope) {
        flow.onEach { channel.send(it) }.launchIn(scope)
    }
}