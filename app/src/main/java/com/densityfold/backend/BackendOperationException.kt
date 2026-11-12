package com.densityfold.backend

import kotlinx.coroutines.CancellationException

class BackendOperationException(cause: Throwable) :
    Exception(cause.message ?: "Ошибка backend", cause)

fun <T> Result<T>.getBackendOrThrow(): T =
    fold(
        onSuccess = { it },
        onFailure = {
            if (it is CancellationException) throw it
            throw BackendOperationException(it)
        }
    )
