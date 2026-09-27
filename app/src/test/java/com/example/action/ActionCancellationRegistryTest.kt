package com.example.action

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCancellationRegistryTest {
    @Test
    fun cancellation_cancels_registered_job_without_throwing() {
        val registry = ActionCancellationRegistry()
        val job = Job()
        registry.register("a1", job)

        assertTrue(registry.isRunning("a1"))
        assertTrue(registry.cancel("a1"))
        assertFalse(job.isActive)
        assertFalse(registry.isRunning("a1"))
    }
}
