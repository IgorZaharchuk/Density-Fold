package com.densityfold.engine

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuCommandRunner {
    private var service: IShizukuService? = null
    private var isBound = false

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
            val rawResult = service?.executeCommand(command)
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
        if (isBound && service != null) return

        suspendCancellableCoroutine { cont ->
            var resumed = false

            // 1. Объявляем lateinit ПЕРЕД использованием
            lateinit var serviceConnection: ServiceConnection
            lateinit var binderReceivedListener: Shizuku.OnBinderReceivedListener

            // 2. Присваиваем реализацию
            binderReceivedListener = Shizuku.OnBinderReceivedListener {
                try {
                    if (!isBound) {
                        Shizuku.bindUserService(userServiceArgs, serviceConnection)
                    } else if (!resumed) {
                        resumed = true
                        // 3. Используем явную ссылку на переменную
                        Shizuku.removeBinderReceivedListener(binderReceivedListener)
                        cont.resume(Unit)
                    }
                } catch (e: Exception) {
                    if (!resumed) {
                        resumed = true
                        // 3. Используем явную ссылку на переменную
                        Shizuku.removeBinderReceivedListener(binderReceivedListener)
                        cont.resumeWithException(e)
                    }
                }
            }

            // 4. Теперь безопасно присваиваем реализацию serviceConnection
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (!resumed) {
                        Shizuku.removeBinderReceivedListener(binderReceivedListener)
                        if (binder != null) {
                            resumed = true
                            service = IShizukuService.Stub.asInterface(binder)
                            isBound = true
                            cont.resume(Unit)
                        } else {
                            resumed = true
                            cont.resumeWithException(IllegalStateException("Binder is null"))
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    isBound = false
                }
            }

            // 5. Регистрируем sticky listener
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)

            // 6. Дополнительная страховка: если сервис уже был привязан до создания корутины
            if (isBound && service != null && !resumed) {
                resumed = true
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                cont.resume(Unit)
            }

            // 7. Очистка при отмене корутины
            cont.invokeOnCancellation {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
            }
        }
    }
}
