package com.densityfold.engine

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import kotlin.coroutines.resume

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
            val result = service?.executeCommand(command)
            if (result != null) {
                Result.success(result)
            } else {
                Result.failure(IllegalStateException("Service returned null"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun ensureBound() {
        if (isBound && service != null) return

        suspendCancellableCoroutine { cont ->
            var resumed = false

            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    // ЖЕСТКАЯ ЗАЩИТА от двойного resume
                    if (!resumed && binder != null) {
                        resumed = true
                        service = IShizukuService.Stub.asInterface(binder)
                        isBound = true
                        cont.resume(Unit)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    isBound = false
                }
            }

            val binderReceivedListener = Shizuku.OnBinderReceivedListener {
                if (!isBound) {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection)
                } else if (!resumed) {
                    // Уже привязан, но корутина ещё не возобновлена (например, отменена и вызвана снова)
                    resumed = true
                    cont.resume(Unit)
                }
            }

            // Sticky listener гарантирует немедленный вызов, если биндер уже получен
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)

            // Дополнительная страховка: если сервис уже был привязан до создания корутины
            if (isBound && service != null && !resumed) {
                resumed = true
                cont.resume(Unit)
            }

            cont.invokeOnCancellation {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
            }
        }
    }
}
