package com.densityfold.engine

import android.util.Log
import android.os.Handler
import android.os.Looper

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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
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

    private const val TAG = "DensityFold-Shizuku"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Обе ссылки изменяются только под stateLock.
    private var service: IShizukuService? = null
    private var connection: ServiceConnection? = null

    // Сериализует bind и полную очистку подключения.
    private val bindMutex = Mutex()
    private val reconnectInProgress = AtomicBoolean(false)

    private const val BIND_TIMEOUT_MS = 10_000L

    // Автоматическое восстановление UserService при перезапуске Shizuku.
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "MAIN BINDER DEAD ${mainBinderState()}")
        detachConnection()
        Log.i(TAG, "Ссылки UserService очищены после смерти Binder")
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "MAIN BINDER RECEIVED ${mainBinderState()}")
        requestReconnect()
    }

    private var lifecycleListenersInstalled = false

    @Synchronized
    fun installLifecycleListeners() {
        if (lifecycleListenersInstalled) return
        lifecycleListenersInstalled = true

        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Log.i(
            TAG,
            "Слушатели жизненного цикла Shizuku установлены ${mainBinderState()}"
        )
    }

    private fun mainBinderState(): String {
        val ping = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            return "pid=${android.os.Process.myPid()} " +
                "mainBinderPing=error:${e.javaClass.simpleName} " +
                "permission=not_checked"
        }

        val permission = if (!ping) {
            "not_checked"
        } else {
            try {
                if (
                    Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) "granted" else "denied"
            } catch (e: Exception) {
                "error:${e.javaClass.simpleName}"
            }
        }

        return "pid=${android.os.Process.myPid()} " +
            "mainBinderPing=$ping permission=$permission"
    }

    fun currentState(): ShizukuRuntimeState {
        val mainBinder = try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

        val permission = if (mainBinder) {
            try {
                Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        val userService = userServiceAvailable()

        return ShizukuRuntimeState(
            mainBinder = mainBinder,
            permission = permission,
            userService = userService
        )
    }

    fun mainBinderAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun permissionGranted(): Boolean {
        return try {
            mainBinderAvailable() &&
                Shizuku.checkSelfPermission() ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun userServiceAvailable(): Boolean {
        val current = synchronized(stateLock) { service }
        return current != null && isUserServiceAlive(current)
    }

    fun requestReconnect() {
        if (!reconnectInProgress.compareAndSet(false, true)) return

        Log.i(TAG, "RECONNECT requested ${mainBinderState()}")
        scope.launch {
            try {
                for (attempt in 0 until 5) {
                val binderAlive = try {
                    Shizuku.pingBinder()
                } catch (_: Exception) {
                    false
                }

                Log.i(
                    TAG,
                    "RECONNECT attempt=${attempt + 1}/5 " +
                        "binderPing=$binderAlive ${mainBinderState()}"
                )

                if (!binderAlive) {
                    Log.w(TAG, "RECONNECT stopped: main Binder unavailable")
                    return@launch
                }

                try {
                    ensureBound()
                    Log.i(TAG, "RECONNECT UserService success ${mainBinderState()}")
                    return@launch
                } catch (e: SecurityException) {
                    Log.e(TAG, "RECONNECT permission error: ${e.message}", e)
                    return@launch
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "RECONNECT attempt ${attempt + 1} failed: ${e.message}",
                        e
                    )
                    if (attempt < 4) delay(1_000L * (attempt + 1))
                }
            }
                Log.e(TAG, "RECONNECT exhausted all attempts ${mainBinderState()}")
            } finally {
                reconnectInProgress.set(false)
            }
        }
    }


    /**
     * Ожидает основной Binder Shizuku и готовность UserService.
     */
    suspend fun awaitReady(timeoutMs: Long = 10_000L): Result<Unit> {
        var lastError: Exception? = null

        return try {
            withTimeout(timeoutMs) {
                while (true) {
                    val binderAlive = try {
                        Shizuku.pingBinder()
                    } catch (_: Exception) {
                        false
                    }

                    if (binderAlive) {
                        val permissionGranted = try {
                            Shizuku.checkSelfPermission() ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                        } catch (_: Exception) {
                            false
                        }

                        if (!permissionGranted) {
                            throw SecurityException(
                                "Разрешение Shizuku не предоставлено"
                            )
                        }

                        try {
                            ensureBound()
                            return@withTimeout
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastError = e
                        }
                    }

                    delay(250L)
                }
            }

            Result.success(Unit)
        } catch (e: TimeoutCancellationException) {
            Result.failure(
                IllegalStateException(
                    "Таймаут ожидания Shizuku (${timeoutMs}ms). " +
                        "Последняя ошибка: ${lastError?.message ?: "Binder недоступен"}",
                    e
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val userServiceArgs = UserServiceArgs(
        ComponentName(
            "com.densityfold",
            "com.densityfold.engine.ShizukuUserService"
        )
    )
        .daemon(true)
        .processNameSuffix("shizuku_service")
        .debuggable(false)
        .version(2)

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

            // Синхронный Binder-вызов не должен блокировать главный поток.
            val rawResult = withContext(Dispatchers.IO) {
                currentService.executeCommand(command)
            }

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
        Log.i(TAG, "USER SERVICE bind start ${mainBinderState()}")

        if (!Shizuku.pingBinder()) {
            throw IllegalStateException(
                "Shizuku binder is not alive"
            )
        }

        if (
            Shizuku.checkSelfPermission() !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "USER SERVICE bind blocked: permission denied ${mainBinderState()}")
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

                    Log.i(
                        TAG,
                        "USER SERVICE onServiceConnected " +
                            "candidate=${candidate != null} ${mainBinderState()}"
                    )

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
                        unbindQuietlyDeferred(this)

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
                    if (wasCurrent) {
                        Log.w(TAG, "USER SERVICE disconnected ${mainBinderState()}")
                        requestReconnect()
                    }
                }

                override fun onBindingDied(
                    name: ComponentName?
                ) {
                    Log.e(TAG, "USER SERVICE onBindingDied ${mainBinderState()}")
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
                    unbindQuietlyDeferred(this)

                    if (shouldResume) {
                        cont.resumeWithException(
                            IllegalStateException("Binding died")
                        )
                    }
                    requestReconnect()
                }

                override fun onNullBinding(
                    name: ComponentName?
                ) {
                    Log.e(TAG, "USER SERVICE onNullBinding ${mainBinderState()}")
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
                    unbindQuietlyDeferred(this)

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
                    if (completed.compareAndSet(false, true)) {
                        if (connection === conn) {
                            connection = null
                            service = null
                        }
                        true
                    } else {
                        false
                    }
                }

                if (timedOut) {
                    Log.e(TAG, "USER SERVICE bind timeout ${mainBinderState()}")
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

            scope.launch {
                try {
                    withContext(Dispatchers.Main.immediate) {
                        val isCurrent = synchronized(stateLock) {
                            connection === conn
                        }

                        if (!completed.get() && isCurrent) {
                            if (!Shizuku.pingBinder()) {
                                throw IllegalStateException(
                                    "Shizuku binder died before bind"
                                )
                            }

                            Shizuku.bindUserService(userServiceArgs, conn)
                        } else if (!completed.get()) {
                            throw IllegalStateException(
                                "UserService connection became stale"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "USER SERVICE bind call failed: ${e.message}", e)
                    var shouldResume = false

                    synchronized(stateLock) {
                        if (connection === conn) {
                            connection = null
                            service = null
                        }

                        shouldResume = completed.compareAndSet(false, true)
                    }

                    timeoutJob.cancel()

                    if (shouldResume) {
                        unbindQuietly(conn)
                        cont.resumeWithException(e)
                    }
                }
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
                    unbindQuietlyDeferred(conn)
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
     * Снимает привязку на главном потоке и дожидается выполнения unbind.
     */
    private suspend fun unbindQuietly(
        conn: ServiceConnection,
        remove: Boolean = false
    ) {
        withContext(Dispatchers.Main.immediate) {
            unbindQuietlyNow(conn, remove)
        }
    }

    /**
     * Откладывает unbind до завершения callback Shizuku.
     */
    private fun unbindQuietlyDeferred(
        conn: ServiceConnection,
        remove: Boolean = false
    ) {
        mainHandler.post {
            unbindQuietlyNow(conn, remove)
        }
    }

    /**
     * Вызывается только на главном потоке.
     */
    private fun unbindQuietlyNow(
        conn: ServiceConnection,
        remove: Boolean = false
    ) {
        try {
            if (!Shizuku.pingBinder()) return

            Shizuku.unbindUserService(
                userServiceArgs,
                conn,
                remove
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
