package com.densityfold.engine

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuCommandRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stateLock = Any()

    // Обе ссылки изменяются только под stateLock.
    private var service: IShizukuService? = null
    private var connection: ServiceConnection? = null

    // Сериализует bind и полную очистку подключения.
    private val bindMutex = Mutex()

    private const val BIND_TIMEOUT_MS = 10_000L

    // Автоматическое восстановление UserService при перезапуске Shizuku.
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        scope.launch { resetConnection() }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        scheduleReconnect()
    }

    init {
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
    }

    private fun scheduleReconnect() {
        scope.launch {
            for (attempt in 0 until 5) {
                if (!Shizuku.pingBinder()) return@launch
                try {
                    ensureBound()
                    return@launch
                } catch (_: SecurityException) {
                    return@launch
                } catch (_: Exception) {
                    if (attempt < 4) delay(1_000L * (attempt + 1))
                }
            }
        }
    }

    private val userServiceArgs = UserServiceArgs(
        ComponentName(
            "com.densityfold",
            "com.densityfold.engine.ShizukuUserService"
        )
    )
        .daemon(false)
        .processNameSuffix("shizuku_service")
        .debuggable(false)
        .version(1)

    suspend fun execute(command: String): Result<String> {
        return try {
            ensureBound()

            val currentService = synchronized(stateLock) {
                service
            } ?: run {
                resetConnection()
                return Result.failure(
                    IllegalStateException("Service is null after binding")
                )
            }

            val binder = currentService.asBinder()

            if (!binder.isBinderAlive || !binder.pingBinder()) {
                resetConnection()
                return Result.failure(
                    IllegalStateException("UserService binder is dead")
                )
            }

            val rawResult = currentService.executeCommand(command)

            if (rawResult == null) {
                resetConnection()
                Result.failure(
                    IllegalStateException("UserService returned null")
                )
            } else {
                parseServiceResult(rawResult)
            }

        } catch (e: CancellationException) {
            throw e

        } catch (e: RemoteException) {
            resetConnection()
            Result.failure(
                Exception("RemoteException: ${e.message ?: "binder died"}", e)
            )

        } catch (e: Exception) {
            resetConnection()
            Result.failure(e)
        }
    }

    private fun parseServiceResult(rawResult: String): Result<String> {
        return when {
            rawResult.startsWith("SUCCESS:") ->
                Result.success(rawResult.substringAfter("SUCCESS:").trim())

            rawResult.startsWith("ERROR:") ->
                Result.failure(
                    Exception(rawResult.substringAfter("ERROR:").trim())
                )

            rawResult.startsWith("EXCEPTION:") ->
                Result.failure(
                    Exception(rawResult.substringAfter("EXCEPTION:").trim())
                )

            else ->
                Result.failure(
                    Exception("Неизвестный формат ответа сервиса: $rawResult")
                )
        }
    }

    private fun isUserServiceAlive(
        currentService: IShizukuService
    ): Boolean {
        return try {
            val binder = currentService.asBinder()
            binder.isBinderAlive && binder.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun ensureBound() {
        bindMutex.withLock {

            if (!Shizuku.pingBinder()) {
                detachConnection()?.let { unbindQuietly(it) }

                throw IllegalStateException(
                    "Shizuku binder is not alive"
                )
            }

            val currentService = synchronized(stateLock) {
                service
            }

            if (
                currentService != null &&
                isUserServiceAlive(currentService) &&
                Shizuku.pingBinder()
            ) {
                return@withLock
            }

            // Старое соединение сначала полностью отсоединяется.
            // Новый bind начнётся только после завершения unbind.
            detachConnection()?.let { unbindQuietly(it) }

            performBind()
        }
    }

    private suspend fun performBind() {
        if (!Shizuku.pingBinder()) {
            throw IllegalStateException(
                "Shizuku binder is not alive"
            )
        }

        if (
            Shizuku.checkSelfPermission() !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(
                "Shizuku permission not granted"
            )
        }

        suspendCancellableCoroutine<Unit> { cont ->

            val completed = AtomicBoolean(false)
            val timeoutJobHolder = AtomicReference<Job?>(null)

            val conn = object : ServiceConnection {

                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?
                ) {
                    val candidate = try {
                        if (
                            binder != null &&
                            binder.isBinderAlive &&
                            binder.pingBinder()
                        ) {
                            IShizukuService.Stub.asInterface(binder)
                        } else {
                            null
                        }
                    } catch (_: Exception) {
                        null
                    }

                    var accepted = false
                    var failed = false
                    var shouldResume = false

                    synchronized(stateLock) {
                        // Callback старого подключения игнорируется.
                        if (connection === this) {
                            if (candidate != null) {
                                service = candidate
                                accepted = true

                                // При повторном подключении после
                                // onServiceDisconnected coroutine
                                // повторно не возобновляем.
                                shouldResume =
                                    completed.compareAndSet(false, true)
                            } else {
                                service = null
                                connection = null
                                failed = true

                                shouldResume =
                                    completed.compareAndSet(false, true)
                            }
                        }
                    }

                    if (accepted) {
                        timeoutJobHolder.get()?.cancel()

                        if (shouldResume) {
                            cont.resume(Unit)
                        }
                    } else if (failed) {
                        timeoutJobHolder.get()?.cancel()
                        unbindQuietly(this)

                        if (shouldResume) {
                            cont.resumeWithException(
                                IllegalStateException(
                                    "UserService binder is null or dead"
                                )
                            )
                        }
                    }
                }

                override fun onServiceDisconnected(
                    name: ComponentName?
                ) {
                    val wasCurrent = synchronized(stateLock) {
                        if (connection === this) {
                            service = null
                            true
                        } else {
                            false
                        }
                    }
                    if (wasCurrent) scheduleReconnect()
                }

                override fun onBindingDied(
                    name: ComponentName?
                ) {
                    var wasCurrent = false
                    var shouldResume = false

                    synchronized(stateLock) {
                        if (connection === this) {
                            wasCurrent = true
                            connection = null
                            service = null

                            shouldResume =
                                completed.compareAndSet(false, true)
                        }
                    }

                    if (!wasCurrent) return

                    timeoutJobHolder.get()?.cancel()
                    unbindQuietly(this)

                    if (shouldResume) {
                        cont.resumeWithException(
                            IllegalStateException("Binding died")
                        )
                    }
                    scheduleReconnect()
                }

                override fun onNullBinding(
                    name: ComponentName?
                ) {
                    var wasCurrent = false
                    var shouldResume = false

                    synchronized(stateLock) {
                        if (connection === this) {
                            wasCurrent = true
                            connection = null
                            service = null

                            shouldResume =
                                completed.compareAndSet(false, true)
                        }
                    }

                    if (!wasCurrent) return

                    timeoutJobHolder.get()?.cancel()
                    unbindQuietly(this)

                    if (shouldResume) {
                        cont.resumeWithException(
                            IllegalStateException(
                                "UserService returned null binding"
                            )
                        )
                    }
                }
            }

            // Публикуем connection до вызова bindUserService.
            synchronized(stateLock) {
                connection = conn
                service = null
            }

            // LAZY позволяет сначала опубликовать Job в holder.
            val timeoutJob = scope.launch(
                start = CoroutineStart.LAZY
            ) {
                delay(BIND_TIMEOUT_MS)

                val timedOut = synchronized(stateLock) {
                    if (
                        connection === conn &&
                        completed.compareAndSet(false, true)
                    ) {
                        connection = null
                        service = null
                        true
                    } else {
                        false
                    }
                }

                if (timedOut) {
                    unbindQuietly(conn)

                    cont.resumeWithException(
                        Exception(
                            "Таймаут подключения к Shizuku " +
                            "(${BIND_TIMEOUT_MS}ms)"
                        )
                    )
                }
            }

            timeoutJobHolder.set(timeoutJob)

            // Повторная проверка закрывает гонку:
            // callback мог завершиться до публикации Job.
            if (completed.get()) {
                timeoutJob.cancel()
            } else {
                timeoutJob.start()
            }

            try {
                Shizuku.bindUserService(userServiceArgs, conn)

            } catch (e: Exception) {
                var shouldResume = false

                synchronized(stateLock) {
                    if (
                        connection === conn &&
                        completed.compareAndSet(false, true)
                    ) {
                        connection = null
                        service = null
                        shouldResume = true
                    }
                }

                timeoutJob.cancel()

                if (shouldResume) {
                    unbindQuietly(conn)
                    cont.resumeWithException(e)
                }

                return@suspendCancellableCoroutine
            }

            cont.invokeOnCancellation {
                timeoutJob.cancel()

                val wasCurrent = synchronized(stateLock) {
                    if (connection === conn) {
                        connection = null
                        service = null
                        completed.compareAndSet(false, true)
                        true
                    } else {
                        false
                    }
                }

                if (wasCurrent) {
                    unbindQuietly(conn)
                }
            }
        }
    }

    /**
     * Отсоединяет только указанное соединение.
     * Старый callback не может очистить новое подключение.
     */
    private fun detachConnection(
        expected: ServiceConnection? = null
    ): ServiceConnection? {
        return synchronized(stateLock) {
            val current = connection ?: return@synchronized null

            if (expected != null && current !== expected) {
                return@synchronized null
            }

            connection = null
            service = null

            current
        }
    }

    /**
     * remove=false снимает привязку, не запрашивая
     * уничтожение самого UserService.
     */
    private fun unbindQuietly(conn: ServiceConnection) {
        try {
            Shizuku.unbindUserService(
                userServiceArgs,
                conn,
                false
            )
        } catch (_: Exception) {
            // Shizuku мог уже потерять собственный Binder.
        }
    }

    /**
     * Полный сброс сериализован с новым bind.
     */
    private suspend fun resetConnection() {
        bindMutex.withLock {
            detachConnection()?.let { unbindQuietly(it) }
        }
    }
}
