package com.densityfold.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.densityfold.FoldMonitorService
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.DisplayInfo
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.DirectDensityController
import com.densityfold.engine.ShizukuCommandRunner
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class UiState(
    val activeDisplay: DisplayInfo? = null,
    val currentDensity: Int = 0,
    val currentDp: Int = 0,
    val innerDp: Int = 900,
    val outerDp: Int = 411,
    val isFoldMonitorEnabled: Boolean = false,
    val isAutoStartEnabled: Boolean = false,
    val isShizukuAvailable: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val diagnosticInfo: String = "",
    val directPermissionGranted: Boolean = false,
    val directTestResult: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = DensityPreferences(application)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        _uiState.value = _uiState.value.copy(
            isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
            errorMessage = null
        )
        viewModelScope.launch { refreshDisplaysInternal() }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        _uiState.value = _uiState.value.copy(
            isShizukuAvailable = false,
            currentDensity = 0,
            currentDp = 0,
            isLoading = false,
            errorMessage = "Shizuku отключён. Автоприменение приостановлено."
        )
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        viewModelScope.launch {
            loadSettingsInternal()
            refreshDisplaysInternal()
        }
    }

    private suspend fun loadSettingsInternal() {
        val innerShortPx = DisplayUtils.getInnerShortPx(getApplication())
        val outerShortPx = DisplayUtils.getOuterShortPx(getApplication())
        preferences.migrateLegacyDpiToDp(innerShortPx, outerShortPx)

        _uiState.value = _uiState.value.copy(
            innerDp = preferences.getInnerDp(),
            outerDp = preferences.getOuterDp(),
            isFoldMonitorEnabled = preferences.isFoldMonitorEnabled(),
            isAutoStartEnabled = preferences.isAutoStartEnabled(),
            isShizukuAvailable = ShizukuManager.isShizukuAvailable()
        )

        updateFoldMonitorService(_uiState.value.isFoldMonitorEnabled)
    }

    private fun updateFoldMonitorService(enabled: Boolean) {
        val intent = Intent(getApplication(), FoldMonitorService::class.java)
        if (enabled) getApplication<Application>().startService(intent)
        else getApplication<Application>().stopService(intent)
    }

    fun setFoldMonitorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setFoldMonitorEnabled(enabled)
            _uiState.value = _uiState.value.copy(isFoldMonitorEnabled = enabled)
            updateFoldMonitorService(enabled)
        }
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoStartEnabled(enabled)
            _uiState.value = _uiState.value.copy(isAutoStartEnabled = enabled)
        }
    }

    private suspend fun refreshDisplaysInternal() {
        val context = getApplication<Application>().applicationContext
        val active = DisplayUtils.getActiveDisplay(context)
        val currentDpi = ShizukuManager.getCurrentDensity().getOrNull() ?: 0
        val currentDp = if (currentDpi > 0 && active != null) DisplayUtils.dpiToDp(currentDpi, active.shortPx) else 0

        _uiState.value = _uiState.value.copy(
            activeDisplay = active,
            currentDensity = currentDpi,
            currentDp = currentDp,
            isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
            directPermissionGranted = context.checkSelfPermission(
                Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED,
            diagnosticInfo = DisplayUtils.getDiagnosticString(context),
            errorMessage = null
        )
    }

    fun refreshDisplays() { viewModelScope.launch { refreshDisplaysInternal() } }

    fun setInnerDp(dp: Int) {
        viewModelScope.launch {
            preferences.setInnerDp(dp)
            _uiState.value = _uiState.value.copy(innerDp = dp)
        }
    }

    fun setOuterDp(dp: Int) {
        viewModelScope.launch {
            preferences.setOuterDp(dp)
            _uiState.value = _uiState.value.copy(outerDp = dp)
        }
    }

    fun applyInnerDensity() {
        viewModelScope.launch {
            applyDensity(
                targetDp = _uiState.value.innerDp,
                isInner = true,
                successMessage = { "Внутренний экран: ${it} dp" },
                bypassMessage = { "Внутренний экран: уже ${it} dp (без изменений)" },
                wrongScreenMessage = "Для применения настроек внутреннего экрана раскройте телефон"
            )
        }
    }

    fun applyOuterDensity() {
        viewModelScope.launch {
            applyDensity(
                targetDp = _uiState.value.outerDp,
                isInner = false,
                successMessage = { "Внешний экран: ${it} dp" },
                bypassMessage = { "Внешний экран: уже ${it} dp (без изменений)" },
                wrongScreenMessage = "Для применения настроек внешнего экрана закройте телефон"
            )
        }
    }

    private suspend fun applyDensity(
        targetDp: Int,
        isInner: Boolean,
        successMessage: (Int) -> String,
        bypassMessage: (Int) -> String,
        wrongScreenMessage: String
    ) {
        val state = _uiState.value
        val context = getApplication<Application>().applicationContext
        val active = DisplayUtils.getActiveDisplay(context)

        if (active == null) {
            _uiState.value = state.copy(errorMessage = "Не удалось определить активный экран")
            return
        }
        if (active.isExternal == isInner) {
            _uiState.value = state.copy(errorMessage = wrongScreenMessage)
            return
        }

        val targetDpi = DisplayUtils.dpToDpi(targetDp, active.shortPx)
        if (targetDpi == null) {
            _uiState.value = state.copy(errorMessage = "Некорректные параметры для расчёта DPI")
            return
        }

        val freshDpiResult = ShizukuManager.getCurrentDensity()
        if (freshDpiResult.isFailure) {
            val error = freshDpiResult.exceptionOrNull()
            _uiState.value = state.copy(
                isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
                errorMessage = error?.message ?: "Не удалось связаться с Shizuku",
                isLoading = false
            )
            return
        }
        val freshDpi = freshDpiResult.getOrNull()
        if (freshDpi != null && freshDpi == targetDpi) {
            _uiState.value = state.copy(
                currentDensity = targetDpi,
                currentDp = DisplayUtils.dpiToDp(targetDpi, active.shortPx),
                successMessage = bypassMessage(targetDp),
                isLoading = false
            )
            return
        }

        _uiState.value = state.copy(isLoading = true, errorMessage = null, successMessage = null)
        val result = ShizukuManager.setDensity(targetDpi)

        result.fold(
            onSuccess = {
                _uiState.value = _uiState.value.copy(
                    currentDensity = targetDpi,
                    currentDp = targetDp,
                    successMessage = successMessage(targetDp),
                    isLoading = false
                )
            },
            onFailure = { _uiState.value = _uiState.value.copy(errorMessage = it.message, isLoading = false) }
        )
    }

    fun grantDirectPermission() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            if (!ShizukuManager.isShizukuAvailable()) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Сначала подключи Shizuku и выдай Density Fold разрешение Shizuku."
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = withContext(Dispatchers.IO) {
                ShizukuCommandRunner.execute(
                    "pm grant com.densityfold android.permission.WRITE_SECURE_SETTINGS"
                )
            }

            val granted = context.checkSelfPermission(
                Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                directPermissionGranted = granted,
                successMessage = if (granted)
                    "WRITE_SECURE_SETTINGS выдан. Для прямого DPI Shizuku больше не требуется."
                else null,
                errorMessage = if (granted) null else
                    "Не удалось подтвердить WRITE_SECURE_SETTINGS. ${result.exceptionOrNull()?.message ?: result.getOrNull().orEmpty()}"
            )
        }
    }

    fun testDirectDensity() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Сначала выдай WRITE_SECURE_SETTINGS."
                )
                return@launch
            }

            val active = DisplayUtils.getActiveDisplay(context)
            if (active == null) {
                _uiState.value = _uiState.value.copy(errorMessage = "Активный экран не определён.")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isLoading = true, errorMessage = null, successMessage = null,
                directTestResult = "Читаю исходное состояние DPI..."
            )

            val rawResult = withContext(Dispatchers.IO) {
                ShizukuCommandRunner.execute("wm density")
            }

            if (rawResult.isFailure) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Не удалось прочитать исходный DPI через Shizuku: ${rawResult.exceptionOrNull()?.message}"
                )
                return@launch
            }

            val raw = rawResult.getOrNull().orEmpty()
            val physical = Regex("Physical density:\\s*(\\d+)")
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val originalOverride = Regex("Override density:\\s*(\\d+)")
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (physical == null || active.density <= 0) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Не удалось надёжно определить исходную плотность. Тест отменён.\n$raw"
                )
                return@launch
            }

            val originalDensity = active.density
            val testDensity = if (originalDensity < 2000) originalDensity + 1 else originalDensity - 1
            var restoreMessage = "Восстановление ещё не выполнено."
            var testMessage = ""

            try {
                _uiState.value = _uiState.value.copy(
                    directTestResult = "Пробую прямой вызов: displayId=${active.displayId}, DPI=$testDensity..."
                )

                // Считаем, что вызов мог изменить состояние даже при исключении.
                val applyResult = withContext(Dispatchers.IO) {
                    DirectDensityController.setDensity(context, active.displayId, testDensity)
                }
                if (applyResult.isFailure) {
                    throw applyResult.exceptionOrNull()
                        ?: IllegalStateException("Неизвестная ошибка прямого вызова")
                }

                delay(1200)
                val after = DisplayUtils.getActiveDisplay(context)
                testMessage = if (after?.density == testDensity) {
                    "Проверка пройдена: метрики экрана показывают DPI=$testDensity."
                } else {
                    "Вызов вернул успех, но метрики не подтвердили изменение. Ожидалось $testDensity, получено ${after?.density}."
                }
            } catch (e: Exception) {
                testMessage = "Прямой вызов завершился ошибкой: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                val restoreResult = withContext(Dispatchers.IO) {
                    if (originalOverride != null) {
                        DirectDensityController.setDensity(context, active.displayId, originalOverride)
                    } else {
                        DirectDensityController.resetDensity(context, active.displayId)
                    }
                }

                val expected = originalOverride ?: physical
                var restoredDensity: Int? = null

                if (restoreResult.isSuccess) {
                    delay(1200)
                    restoredDensity = DisplayUtils.getActiveDisplay(context)?.density
                    if (restoredDensity == expected) {
                        restoreMessage = "Исходное состояние восстановлено и проверено: DPI=$expected."
                    }
                }

                // Если прямое восстановление не подтвердилось метриками — откат через Shizuku.
                if (restoredDensity != expected) {
                    val fallbackCommand = if (originalOverride != null) {
                        "wm density $originalOverride"
                    } else {
                        "wm density reset"
                    }
                    val fallback = withContext(Dispatchers.IO) {
                        ShizukuCommandRunner.execute(fallbackCommand)
                    }
                    delay(1200)
                    val restored = DisplayUtils.getActiveDisplay(context)
                    val fallbackVerified = fallback.isSuccess && restored?.density == expected

                    restoreMessage = if (fallbackVerified) {
                        "Прямое восстановление не подтвердилось (${restoreResult.exceptionOrNull()?.message ?: "метрики не совпали"}). Резервный откат через Shizuku выполнен и проверен: DPI=$expected."
                    } else {
                        "КРИТИЧНО: исходный DPI не восстановлен. Ожидалось $expected, получено ${restored?.density}. Прямой вызов: ${restoreResult.exceptionOrNull()?.message ?: "метрики не совпали"}; резервный откат: ${fallback.exceptionOrNull()?.message ?: fallback.getOrNull().orEmpty()}"
                    }
                }
            }

            val finalDisplay = DisplayUtils.getActiveDisplay(context)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                currentDensity = finalDisplay?.density ?: 0,
                currentDp = if (finalDisplay != null && finalDisplay.density > 0)
                    DisplayUtils.dpiToDp(finalDisplay.density, finalDisplay.shortPx) else 0,
                directTestResult = "$testMessage\n$restoreMessage",
                successMessage = if (testMessage.startsWith("Проверка пройдена") &&
                    restoreMessage.startsWith("Исходное состояние восстановлено")
                ) "Прямой DPI-вызов сработал, исходная плотность восстановлена." else null,
                errorMessage = if (restoreMessage.startsWith("КРИТИЧНО")) restoreMessage else null
            )
        }
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        super.onCleared()
    }

    fun requestShizukuPermission() {
        try {
            if (rikka.shizuku.Shizuku.getUid() != -1) {
                rikka.shizuku.Shizuku.requestPermission(1001)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
