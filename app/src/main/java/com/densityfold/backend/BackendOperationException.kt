package com.densityfold.backend

import kotlinx.coroutines.CancellationException

internal fun shouldInvalidateBackend(error: Throwable): Boolean =
    error is BackendOperationException && error.invalidatesBackend

class BackendOperationException(
    cause: Throwable,
    val invalidatesBackend: Boolean = false
) : Exception(cause.message ?: "Ошибка backend", cause)

fun <T> Result<T>.getBackendOrThrow(
    invalidateBackend: Boolean = false
): T =
    fold(
        onSuccess = { it },
        onFailure = {
            if (it is CancellationException) throw it
            throw BackendOperationException(it, invalidateBackend)
        }
    )
