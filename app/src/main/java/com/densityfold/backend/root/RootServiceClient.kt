package com.densityfold.backend.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object RootServiceClient {

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

    suspend fun getProcessUid(): Int = withContext(Dispatchers.IO) {
        service().getProcessUid()
    }

    suspend fun getCurrentDensity(displayId: Int): Int =
        withContext(Dispatchers.IO) {
            service().getCurrentDensity(displayId)
        }

    suspend fun setDensity(displayId: Int, dpi: Int): Int =
        withContext(Dispatchers.IO) {
            service().setDensity(displayId, dpi)
        }

    private suspend fun service(): IRootDensityService =
        connectionMutex.withLock {
            remote?.let { return@withLock it }

            val context = appContext
                ?: throw IllegalStateException("RootServiceClient не инициализирован")

            var newConnection: ServiceConnection? = null

            try {
                withContext(Dispatchers.Main.immediate) {
                    withTimeout(CONNECT_TIMEOUT_MS) {
                        suspendCancellableCoroutine<IRootDensityService> { continuation ->
                            val connection = object : ServiceConnection {
                                override fun onServiceConnected(
                                    name: ComponentName,
                                    binder: IBinder
                                ) {
                                    if (activeConnection !== this) return

                                    val service =
                                        IRootDensityService.Stub.asInterface(binder)

                                    if (service == null) {
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
                                    if (activeConnection === this) {
                                        remote = null
                                        activeConnection = null
                                    }
                                }

                                override fun onBindingDied(name: ComponentName) {
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
                cleanup(newConnection)
                throw IllegalStateException(
                    "Таймаут запуска Root daemon (${CONNECT_TIMEOUT_MS} мс)",
                    error
                )
            } catch (error: CancellationException) {
                cleanup(newConnection)
                throw error
            } catch (error: Exception) {
                cleanup(newConnection)
                throw error
            }

            remote ?: throw IllegalStateException(
                "Root daemon подключился без доступного Binder"
            )
        }

    private suspend fun cleanup(connection: ServiceConnection?) {
        if (connection == null) return

        withContext(Dispatchers.Main.immediate) {
            if (activeConnection === connection) {
                activeConnection = null
                remote = null
            }
            try {
                RootService.unbind(connection)
            } catch (_: Exception) {
                // Соединение могло не успеть зарегистрироваться.
            }
        }
    }
}
