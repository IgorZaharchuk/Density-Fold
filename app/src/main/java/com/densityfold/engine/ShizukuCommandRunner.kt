package com.densityfold.engine

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuCommandRunner {
    private var service: IShizukuService? = null
    private val bindMutex = Mutex()

    private val userServiceArgs = UserServiceArgs(
        ComponentName("com.densityfold", "com.densityfold.engine.ShizukuUserService")
    )
        .daemon(false)
        .processNameSuffix("shizuku_service")
        .debuggable(false)
        .version(1)

    suspend fun execute(command: String): Result<String> {
        return try {
            ensureBound()
            val currentService = service
                ?: return Result.failure(IllegalStateException("Service is null after binding"))
            val rawResult = currentService.executeCommand(command)
            if (rawResult == null) {
                Result.failure(IllegalStateException("Service returned null"))
            } else {
                parseServiceResult(rawResult)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseServiceResult(rawResult: String): Result<String> {
        return when {
            rawResult.startsWith("SUCCESS:") -> Result.success(rawResult.substringAfter("SUCCESS:").trim())
            rawResult.startsWith("ERROR:") -> Result.failure(Exception(rawResult.substringAfter("ERROR:").trim()))
            rawResult.startsWith("EXCEPTION:") -> Result.failure(Exception(rawResult.substringAfter("EXCEPTION:").trim()))
            else -> Result.failure(Exception("Неизвестный формат ответа сервиса: $rawResult"))
        }
    }

    private suspend fun ensureBound() {
        bindMutex.withLock {
            // Проверяем, что Shizuku вообще жив
            if (!Shizuku.pingBinder()) {
                service = null
                throw IllegalStateException("Shizuku binder is not alive")
            }

            // Если сервис уже привязан — выходим
            if (service != null) {
                return
            }

            // Привязываем сервис через suspendCancellableCoroutine
            suspendCancellableCoroutine<Unit> { cont ->
                var resumed = false

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (!resumed) {
                            resumed = true
                            if (binder != null) {
                                service = IShizukuService.Stub.asInterface(binder)
                                cont.resume(Unit)
                            } else {
                                cont.resumeWithException(IllegalStateException("Binder is null"))
                            }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                    }
                }

                Shizuku.bindUserService(userServiceArgs, connection)

                cont.invokeOnCancellation {
                    // Shizuku сам управляет жизненным циклом сервиса
                }
            }
        }
    }
}
