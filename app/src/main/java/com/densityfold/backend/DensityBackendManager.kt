package com.densityfold.backend

import com.densityfold.backend.root.RootBackend
import com.densityfold.backend.shizuku.ShizukuBackend
import com.densityfold.backend.shizuku.ShizukuManager
import com.densityfold.backend.shizuku.ShizukuMonitor
import com.densityfold.data.DensitySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BackendAvailabilityState(
    val rootEnabled: Boolean = false,
    val rootAvailable: Boolean? = null,
    val shizukuEnabled: Boolean = false,
    val shizukuAvailable: Boolean? = null
)

object DensityBackendManager {

    private val selectionMutex = Mutex()

    private val _availability = MutableStateFlow(BackendAvailabilityState())
    val availability: StateFlow<BackendAvailabilityState> = _availability.asStateFlow()

    @Volatile
    private var activeBackend: DensityBackend? = null

    private val _selectedBackend = MutableStateFlow<DensityBackend?>(null)
    val selectedBackend: StateFlow<DensityBackend?> = _selectedBackend.asStateFlow()

    private fun publishActiveBackend(backend: DensityBackend?) {
        activeBackend = backend
        _selectedBackend.value = backend
    }

    suspend fun selectBackend(
        settingsProvider: suspend () -> DensitySettings,
        timeoutMs: Long = 10_000L
    ): Result<DensityBackend> = selectionMutex.withLock {
        val settings = settingsProvider()
        selectBackendLocked(
            settings.rootBackendEnabled,
            settings.shizukuBackendEnabled,
            timeoutMs
        )
    }

    /**
     * Вся операция выполняется через один backend.
     * Переключение настроек ждёт завершения текущей операции.
     */
    suspend fun <T> withBackend(
        settingsProvider: suspend () -> DensitySettings,
        timeoutMs: Long = 10_000L,
        operation: suspend (DensityBackend) -> T
    ): T = selectionMutex.withLock {
        val settings = settingsProvider()
        val rootEnabled = settings.rootBackendEnabled
        val shizukuEnabled = settings.shizukuBackendEnabled
        val current = activeBackend
        val backend = if (
            current != null && isEnabled(current, rootEnabled, shizukuEnabled)
        ) {
            current
        } else {
            selectBackendLocked(rootEnabled, shizukuEnabled, timeoutMs).getOrThrow()
        }

        try {
            operation(backend)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendOperationException) {
            if (activeBackend === backend) publishActiveBackend(null)
            throw e
        }
    }

    private fun isEnabled(
        backend: DensityBackend,
        rootEnabled: Boolean,
        shizukuEnabled: Boolean
    ): Boolean = when (backend) {
        RootBackend -> rootEnabled
        ShizukuBackend -> shizukuEnabled
        else -> false
    }

    private suspend fun selectBackendLocked(
        rootEnabled: Boolean,
        shizukuEnabled: Boolean,
        timeoutMs: Long
    ): Result<DensityBackend> {
        _availability.value = BackendAvailabilityState(
            rootEnabled = rootEnabled,
            shizukuEnabled = shizukuEnabled
        )

        if (!rootEnabled && !shizukuEnabled) {
            publishActiveBackend(null)
            stopShizukuSafely()
            return Result.failure(IllegalStateException("Все backend отключены"))
        }

        var rootError: Throwable? = null
        var shizukuError: Throwable? = null

        // Root всегда проверяется первым при явном выборе.
        if (rootEnabled) {
            val result = RootBackend.awaitReady(timeoutMs)
            _availability.value = _availability.value.copy(
                rootAvailable = result.isSuccess
            )
            if (result.isSuccess) {
                stopShizukuSafely()
                publishActiveBackend(RootBackend)
                return Result.success(RootBackend)
            }
            rootError = result.exceptionOrNull()
        }

        // Shizuku запускается только при необходимости fallback.
        if (shizukuEnabled) {
            try {
                ShizukuManager.start()
                ShizukuMonitor.start()
                val result = ShizukuBackend.awaitReady(timeoutMs)
                _availability.value = _availability.value.copy(
                    shizukuAvailable = result.isSuccess
                )
                if (result.isSuccess) {
                    publishActiveBackend(ShizukuBackend)
                    return Result.success(ShizukuBackend)
                }
                shizukuError = result.exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shizukuError = e
                _availability.value = _availability.value.copy(
                    shizukuAvailable = false
                )
            }
        } else {
            stopShizukuSafely()
        }

        publishActiveBackend(null)
        val reasons = buildList {
            if (rootEnabled) add("Root: ${rootError?.message ?: "недоступен"}")
            if (shizukuEnabled) add("Shizuku: ${shizukuError?.message ?: "недоступен"}")
        }

        return Result.failure(
            IllegalStateException(
                "Не удалось выбрать backend. ${reasons.joinToString("; ")}",
                shizukuError ?: rootError
            )
        )
    }

    private suspend fun stopShizukuSafely() {
        ShizukuMonitor.stop()
        try {
            ShizukuManager.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ошибка остановки Shizuku не должна мешать работе Root.
        }
    }

}
