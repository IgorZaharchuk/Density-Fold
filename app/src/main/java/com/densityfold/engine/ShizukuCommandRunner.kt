package com.densityfold.engine

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.densityfold.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

object ShizukuCommandRunner {
    private var service: IShizukuService? = null
    private var isBound = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.densityfold", "com.densityfold.engine.ShizukuUserService")
    )
        .daemon(false)
        .processNameSuffix("shizuku_service")
        .debuggable(false)
        .version(1)

    suspend fun ensureBound(): Boolean {
        if (isBound && service != null) return true
        
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                try {
                    Shizuku.bindUserService(userServiceArgs, object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            service = IShizukuService.Stub.asInterface(binder)
                            isBound = true
                            continuation.resume(true)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                            isBound = false
                        }
                    })
                } catch (e: Exception) {
                    continuation.resume(false)
                }
            }
        }
    }

    suspend fun execute(command: String): Result<String> {
        return try {
            if (!ensureBound()) {
                return Result.failure(Exception("Не удалось подключиться к Shizuku UserService"))
            }
            
            val result = service?.executeCommand(command) ?: return Result.failure(Exception("Сервис не инициализирован"))
            
            when {
                result.startsWith("SUCCESS:") -> Result.success(result.substring(8))
                result.startsWith("ERROR:") -> {
                    val parts = result.substring(6).split(":", limit = 2)
                    Result.failure(Exception("Код ${parts[0]}: ${parts.getOrElse(1) { "" }}"))
                }
                result.startsWith("EXCEPTION:") -> Result.failure(Exception(result.substring(10)))
                else -> Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
