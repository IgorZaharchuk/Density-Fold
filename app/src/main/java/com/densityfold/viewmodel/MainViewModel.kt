package com.densityfold.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.densityfold.FoldMonitorService
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.DisplayInfo
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val activeDisplay: DisplayInfo? = null,
    val currentDensity: Int = 0,
    val currentDp: Int = 0,
    val innerDp: Int = 900,
    val outerDp: Int = 411,
    val isFoldMonitorEnabled: Boolean = false,
    val isShizukuAvailable: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val diagnosticInfo: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = DensityPreferences(application)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
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

    /**
     * Общая логика применения DPI. Устраняет дублирование между applyInner/applyOuter.
     */
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

        val freshDpi = ShizukuManager.getCurrentDensity().getOrNull()
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
