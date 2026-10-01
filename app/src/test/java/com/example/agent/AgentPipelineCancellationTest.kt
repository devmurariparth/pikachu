package com.example.agent

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentPipelineCancellationTest {
    @Test fun cancelling_invocation_cancels_the_agent_planner_before_action_execution() = runBlocking {
        val plannerStarted = CompletableDeferred<Unit>()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val invocation = async(Dispatchers.Unconfined) {
            AgentPipeline().execute(
                context = context,
                command = "Explain an unfamiliar topic",
                planner = { _, _ ->
                    plannerStarted.complete(Unit)
                    awaitCancellation()
                }
            )
        }

        withTimeout(2_000) { plannerStarted.await() }
        invocation.cancelAndJoin()
        assertTrue(invocation.isCancelled)
    }
}
