package com.densityfold.engine

import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuCommandRunner {
    
    private var isBound = false
    private var service: Any? = null
    
    suspend fun execute(command: String): Result<String> {
        return try {
            ensureBound()
            val result = executeCommand(command)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private suspend fun ensureBound() {
        if (isBound) return
        
        suspendCancellableCoroutine { cont ->
            var resumed = false
            
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    if (!resumed) {
                        resumed = true
                        isBound = true
                        Shizuku.removeBinderReceivedListener(this)
                        cont.resume(Unit)
                    }
                }
            }
            
            Shizuku.addBinderReceivedListener(listener)
            
            // Если уже привязан — сразу завершаем
            if (Shizuku.pingBinder()) {
                if (!resumed) {
                    resumed = true
                    isBound = true
                    Shizuku.removeBinderReceivedListener(listener)
                    cont.resume(Unit)
                }
            }
            
            cont.invokeOnCancellation {
                Shizuku.removeBinderReceivedListener(listener)
            }
        }
    }
    
    private fun executeCommand(command: String): String {
        // Здесь должна быть реальная логика выполнения команды через Shizuku
        // Пока возвращаем заглушку — замени на реальный код
        return "Command executed: $command"
    }
}
