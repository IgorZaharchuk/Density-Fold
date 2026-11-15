package com.densityfold.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendInvalidationPolicyTest {

    @Test
    fun readinessFailureInvalidatesBackend() {
        val error = BackendOperationException(
            IllegalStateException("Backend is not ready"),
            invalidatesBackend = true
        )
        assertTrue(shouldInvalidateBackend(error))
    }

    @Test
    fun ordinaryOperationFailureDoesNotInvalidateBackend() {
        val error = BackendOperationException(
            IllegalStateException("Density command failed")
        )
        assertFalse(shouldInvalidateBackend(error))
    }

    @Test
    fun unrelatedFailureDoesNotInvalidateBackend() {
        assertFalse(
            shouldInvalidateBackend(
                IllegalStateException("Unrelated failure")
            )
        )
    }
}
