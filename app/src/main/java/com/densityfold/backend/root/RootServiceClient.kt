package com.densityfold.backend.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import java.io.File
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
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

    private fun logRootEnvironment(stage: String) {
        val path = System.getenv("PATH")
        Log.i(TAG, "ROOT ENV [$stage]: pid=${Process.myPid()}, uid=${Process.myUid()}")
        Log.i(TAG, "ROOT ENV [$stage]: PATH=${path ?: "<null>"}")

        if (path == null) {
            Log.w(TAG, "ROOT ENV [$stage]: PATH отсутствует")
            return
        }

        path.split(':').forEachIndexed { index, entry ->
            val directory = if (entry.isEmpty()) "." else entry
            val su = File(directory, "su")
            Log.i(
                TAG,
                "ROOT ENV [$stage]: PATH[$index]=$directory, suExists=${su.exists()}, suCanExecute=${su.canExecute()}"
            )
        }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remote: IRootDensityService? = null

    private var activeConnection: ServiceConnection? = null
    private val connectionMutex = Mutex()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "initialize: application context установлен")
        logRootEnvironment("initialize")
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

    private suspend fun awaitSuExecutable() {
        var warningLogged = false

        while (true) {
            val path = System.getenv("PATH").orEmpty()
            val found = path.split(':').any { entry ->
                val directory = if (entry.isEmpty()) "." else entry
                File(directory, "su").canExecute()
            }

            if (found) {
                Log.i(TAG, "service: исполняемый su обнаружен в PATH")
                return
            }

            if (!warningLogged) {
                Log.w(TAG, "service: su пока отсутствует в PATH; RootService.bind() отложен")
                warningLogged = true
            }

            delay(500L)
        }
    }

    private suspend fun service(): IRootDensityService =
        connectionMutex.withLock {
            Log.i(TAG, "service: запрос подключения; active=${activeConnection != null}, remote=${remote != null}")
            remote?.let {
                Log.i(TAG, "service: используем существующий remote Binder")
                return@withLock it
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
                        Log.i(TAG, "service: su обнаружен; ожидание Binder")
                        suspendCancellableCoroutine<IRootDensityService> { continuation ->
                            val connection = object : ServiceConnection {
                                override fun onServiceConnected(
                                    name: ComponentName,
                                    binder: IBinder
                                ) {
                                    Log.i(TAG, "onServiceConnected: binderAlive=${binder.isBinderAlive}, connection=${System.identityHashCode(this)}")
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
                                    Log.i(TAG, "onServiceConnected: remote сохранён, continuationActive=${continuation.isActive}")
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
                            Log.i(TAG, "bind: создаём connection=${System.identityHashCode(connection)}")

                            val intent = Intent(
                                context,
                                RootDensityService::class.java
                            ).addCategory(RootService.CATEGORY_DAEMON_MODE)

                            Log.i(TAG, "bind: вызываем RootService.bind(), component=${intent.component}")
                            RootService.bind(intent, connection)
                            Log.i(TAG, "bind: RootService.bind() вернулся")
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                Log.e(TAG, "service: TIMEOUT; connection=${newConnection?.let { System.identityHashCode(it) }}", error)
                cleanup(newConnection)
                val message = if (waitingForSu) {
                    "Таймаут ожидания исполняемого su в PATH (${CONNECT_TIMEOUT_MS} мс); RootService.bind() не вызывался"
                } else {
                    "Таймаут запуска Root daemon (${CONNECT_TIMEOUT_MS} мс)"
                }
                throw IllegalStateException(message, error)
            } catch (error: CancellationException) {
                Log.w(TAG, "service: CANCELLED", error)
                cleanup(newConnection)
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "service: EXCEPTION", error)
                cleanup(newConnection)
                throw error
            }

            Log.i(TAG, "service: ожидание завершено; remote=${remote != null}")
            remote ?: throw IllegalStateException(
                "Root daemon подключился без доступного Binder"
            )
        }

    private suspend fun cleanup(connection: ServiceConnection?) {
        Log.w(TAG, "cleanup: вызван; connection=${connection?.let { System.identityHashCode(it) }}")
        if (connection == null) return

        withContext(Dispatchers.Main.immediate) {
            if (activeConnection === connection) {
                activeConnection = null
                remote = null
            }
            try {
                Log.i(TAG, "cleanup: вызываем RootService.unbind()")
                RootService.unbind(connection)
                Log.i(TAG, "cleanup: RootService.unbind() завершён")
            } catch (error: Exception) {
                Log.e(TAG, "cleanup: unbind завершился ошибкой", error)
                // Соединение могло не успеть зарегистрироваться.
            }
        }
    }
}
