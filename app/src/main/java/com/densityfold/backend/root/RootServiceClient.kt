package com.densityfold.backend.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import java.io.File
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object RootServiceClient {

    private const val TAG = "DensityFoldRoot"
    private const val CONNECT_TIMEOUT_MS = 20_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remote: IRootDensityService? = null

    private var activeConnection: ServiceConnection? = null
    private val connectionMutex = Mutex()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun getProcessUid(): Int =
        withRemote { it.getProcessUid() }

    suspend fun getCurrentDensity(displayId: Int): Int =
        withRemote { it.getCurrentDensity(displayId) }

    suspend fun setDensity(displayId: Int, dpi: Int): Int =
        withRemote { it.setDensity(displayId, dpi) }

    private suspend fun <T> withRemote(
        operation: (IRootDensityService) -> T
    ): T {
        val current = service()
        return try {
            withContext(Dispatchers.IO) {
                operation(current)
            }
        } catch (error: RemoteException) {
            Log.e(TAG, "Root Binder: транспортная ошибка; сбрасываем соединение", error)
            invalidate(current)
            throw error
        }
    }

    private suspend fun invalidate(expected: IRootDensityService) {
        var connectionToUnbind: ServiceConnection? = null

        connectionMutex.withLock {
            if (remote === expected) {
                remote = null
                connectionToUnbind = activeConnection
                activeConnection = null
            }
        }

        cleanup(connectionToUnbind)
    }

    private suspend fun awaitSuExecutable() {
        var warningLogged = false

        while (true) {
            val path = System.getenv("PATH").orEmpty()
            val found = path.split(':').any { entry ->
                val directory = if (entry.isEmpty()) "." else entry
                File(directory, "su").canExecute()
            }

            if (found) {
                return
            }

            if (!warningLogged) {
                Log.w(TAG, "service: su пока отсутствует в PATH; RootService.bind() отложен")
                warningLogged = true
            }

            delay(500L)
        }
    }

    private suspend fun service(): IRootDensityService {
        while (true) {
            var staleConnection: ServiceConnection? = null

            val result = connectionMutex.withLock {
                remote?.let { cached ->
                val binder = cached.asBinder()
                val alive = try {
                    binder.isBinderAlive && binder.pingBinder()
                } catch (_: Exception) {
                    false
                }

                if (alive) {
                    return@withLock cached
                }

                Log.w(TAG, "Root Binder мёртв; сбрасываем старое соединение")
                remote = null
                staleConnection = activeConnection
                activeConnection = null
                return@withLock null
            }

            val context = appContext
                ?: throw IllegalStateException("RootServiceClient не инициализирован")

            var newConnection: ServiceConnection? = null

            var waitingForSu = false
            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    waitingForSu = true
                    awaitSuExecutable()
                    waitingForSu = false

                    withContext(Dispatchers.Main.immediate) {
                        suspendCancellableCoroutine<IRootDensityService> { continuation ->
                            val connection = object : ServiceConnection {
                                override fun onServiceConnected(
                                    name: ComponentName,
                                    binder: IBinder
                                ) {
                                    if (activeConnection !== this) {
                                        Log.w(TAG, "onServiceConnected: устаревшее соединение")
                                        return
                                    }

                                    val service =
                                        IRootDensityService.Stub.asInterface(binder)

                                    if (service == null) {
                                        Log.e(TAG, "onServiceConnected: Stub.asInterface вернул null")
                                        continuation.resumeWithException(
                                            IllegalStateException(
                                                "Root-сервис вернул пустой Binder"
                                            )
                                        )
                                        return
                                    }

                                    remote = service
                                    if (continuation.isActive) {
                                        continuation.resume(service)
                                    }
                                }

                                override fun onServiceDisconnected(name: ComponentName) {
                                    Log.w(TAG, "onServiceDisconnected: $name")
                                    if (activeConnection === this) {
                                        remote = null
                                        activeConnection = null
                                    }
                                }

                                override fun onBindingDied(name: ComponentName) {
                                    Log.e(TAG, "onBindingDied: $name")
                                    if (activeConnection === this) {
                                        remote = null
                                        activeConnection = null
                                    }
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            IllegalStateException(
                                                "Соединение с Root daemon потеряно"
                                            )
                                        )
                                    }
                                }

                                override fun onNullBinding(name: ComponentName) {
                                    Log.e(TAG, "onNullBinding: $name")
                                    if (activeConnection === this) {
                                        remote = null
                                        activeConnection = null
                                    }
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            IllegalStateException(
                                                "Root daemon не предоставил Binder"
                                            )
                                        )
                                    }
                                }
                            }

                            newConnection = connection
                            activeConnection = connection

                            val intent = Intent(
                                context,
                                RootDensityService::class.java
                            ).addCategory(RootService.CATEGORY_DAEMON_MODE)

                            RootService.bind(intent, connection)
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                Log.e(TAG, "service: TIMEOUT", error)
                cleanup(newConnection)
                val message = if (waitingForSu) {
                    "Таймаут ожидания исполняемого su в PATH; RootService.bind() не вызывался"
                } else {
                    "Таймаут запуска Root daemon"
                }
                throw IllegalStateException(message, error)
            } catch (error: CancellationException) {
                cleanup(newConnection)
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "service: EXCEPTION", error)
                cleanup(newConnection)
                throw error
            }

            remote ?: throw IllegalStateException(
                "Root daemon подключился без доступного Binder"
            )
            }

            if (result != null) return result

            cleanup(staleConnection)
        }
    }

    private suspend fun cleanup(connection: ServiceConnection?) {
        if (connection == null) return

        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (activeConnection === connection) {
                activeConnection = null
                remote = null
            }
            try {
                RootService.unbind(connection)
            } catch (error: Exception) {
                Log.e(TAG, "cleanup: unbind завершился ошибкой", error)
                // Соединение могло не успеть зарегистрироваться.
            }
        }
    }
}
