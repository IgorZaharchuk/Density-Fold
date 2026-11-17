package com.densityfold.backend.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.content.ServiceConnection

import com.densityfold.engine.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

import java.util.concurrent.atomic.AtomicBoolean

object ShizukuPermissionGrant {

    private const val EXPECTED_PACKAGE = "com.densityfold"
    private const val PERMISSION =
        "android.permission.WRITE_SECURE_SETTINGS"

    private const val CONNECTION_TIMEOUT_MS = 10_000L
    private const val COMMAND_TIMEOUT_MS = 20_000L

    suspend fun grant(context: Context): Result<Unit> {
        return try {
            require(context.packageName == EXPECTED_PACKAGE) {
                "Неверный package приложения: ${context.packageName}"
            }

            if (context.checkSelfPermission(PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                return Result.success(Unit)
            }

            check(Shizuku.pingBinder()) {
                "Shizuku не запущен или Binder недоступен"
            }

            check(
                Shizuku.checkSelfPermission() ==
                    PackageManager.PERMISSION_GRANTED
            ) {
                "Не выдано разрешение Shizuku. Сначала подтвердите запрос в Shizuku."
            }

            val args = UserServiceArgs(
                ComponentName(
                    context.packageName,
                    ShizukuUserService::class.java.name
                )
            )
                .daemon(false)
                .processNameSuffix("permission")
                .debuggable(false)
                .version(1)

            val deferred = CompletableDeferred<IShizukuService>()
            val bound = AtomicBoolean(false)

            val connection = object : ServiceConnection {

                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?
                ) {
                    if (service == null) {
                        deferred.completeExceptionally(
                            IllegalStateException("Shizuku вернул пустой Binder")
                        )
                        return
                    }

                    val remote = IShizukuService.Stub.asInterface(service)

                    if (remote == null || !service.isBinderAlive) {
                        deferred.completeExceptionally(
                            IllegalStateException("Binder UserService недоступен")
                        )
                        return
                    }

                    deferred.complete(remote)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    deferred.completeExceptionally(
                        IllegalStateException("Соединение с UserService потеряно")
                    )
                }

                override fun onBindingDied(name: ComponentName?) {
                    deferred.completeExceptionally(
                        IllegalStateException("Привязка UserService завершилась")
                    )
                }

                override fun onNullBinding(name: ComponentName?) {
                    deferred.completeExceptionally(
                        IllegalStateException("UserService вернул пустое подключение")
                    )
                }
            }

            try {
                withContext(Dispatchers.Main.immediate) {
                    // Устанавливаем флаг до bind: при частичной ошибке
                    // finally всё равно попробует выполнить unbind.
                    bound.set(true)
                    Shizuku.bindUserService(args, connection)
                }

                val remote = withTimeout(CONNECTION_TIMEOUT_MS) {
                    deferred.await()
                }

                val response = withTimeout(COMMAND_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        remote.grantWriteSecureSettings(context.packageName)
                    }
                }

                check(response.startsWith("SUCCESS:")) {
                    response.removePrefix("ERROR:")
                        .removePrefix("EXCEPTION:")
                        .ifBlank { "Shizuku не смог выдать разрешение" }
                }

                check(
                    context.checkSelfPermission(PERMISSION) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    "Команда завершилась, но WRITE_SECURE_SETTINGS не выдано"
                }

                Result.success(Unit)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (bound.compareAndSet(true, false)) {
                        try {
                            Shizuku.unbindUserService(args, connection, false)
                        } catch (_: Exception) {
                            // Binder мог отключиться до завершения очистки.
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
