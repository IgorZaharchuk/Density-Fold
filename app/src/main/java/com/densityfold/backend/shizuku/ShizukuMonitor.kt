package com.densityfold.backend.shizuku

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Наблюдает за состоянием Shizuku только пока выбран этот backend.
 * Передаёт состояние Binder в ShizukuRecovery.
 * Восстановление UserService делегируется runner.
 */
object ShizukuMonitor {

    private const val TAG = "DensityFold-ShizukuMonitor"
    private const val POLL_INTERVAL_MS = 3_000L
    private const val RECONNECT_INTERVAL_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _runtimeState =
        MutableStateFlow<ShizukuRuntimeState?>(null)

    val runtimeState: StateFlow<ShizukuRuntimeState?> =
        _runtimeState.asStateFlow()

    @Volatile
    private var monitorJob: Job? = null

    @Synchronized
    fun start() {
        if (monitorJob?.isActive == true) return

        _runtimeState.value = null

        monitorJob = scope.launch {
            var lastReconnectAt = 0L


            while (isActive) {
                val state = ShizukuCommandRunner.currentState()

                _runtimeState.value = state
                ShizukuRecovery.onRuntimeState(state)

                if (
                    state.mainBinder &&
                    state.permission &&
                    !state.userService
                ) {
                    val now = SystemClock.elapsedRealtime()

                    if (now - lastReconnectAt >= RECONNECT_INTERVAL_MS) {
                        lastReconnectAt = now
                        Log.w(TAG, "UserService недоступен, запрашиваем reconnect")
                        ShizukuCommandRunner.requestReconnect()
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    suspend fun stop() {
        val job = synchronized(this) {
            monitorJob.also { monitorJob = null }
        }

        try {
            job?.cancelAndJoin()
        } catch (e: CancellationException) {
            throw e
        } finally {
            _runtimeState.value = null
            ShizukuRecovery.reset()
        }

    }
}
