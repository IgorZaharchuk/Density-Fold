package com.densityfold.viewmodel

import android.app.Application
import android.content.Intent
import android.provider.Settings
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
import rikka.shizuku.Shizuku

data class UiState(
    val activeDisplay: DisplayInfo? = null,
    val currentDensity: Int = 0,
    val currentDp: Int = 0,
    val innerDpi: Int = 350,
    val outerDpi: Int = 420,
    val innerShortPx: Int = 1968,
    val outerShortPx: Int = 1080,
    val isFoldMonitorEnabled: Boolean = false,
    val isAutoStartEnabled: Boolean = false,
    val isShizukuAvailable: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isOverlayPermissionGranted: Boolean = false
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

        _uiState.value = _uiState.value.copy(
            innerDpi = preferences.getInnerDpi(),
            outerDpi = preferences.getOuterDpi(),
            innerShortPx = innerShortPx,
            outerShortPx = outerShortPx,
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
        val currentDp = if (currentDpi > 0 && active != null) {
            DisplayUtils.dpiToDp(currentDpi, active.shortPx)
        } else 0

        _uiState.value = _uiState.value.copy(
            activeDisplay = active,
            currentDensity = currentDpi,
            currentDp = currentDp,
            innerShortPx = DisplayUtils.getInnerShortPx(context),
            outerShortPx = DisplayUtils.getOuterShortPx(context),
            isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
            isOverlayPermissionGranted = Settings.canDrawOverlays(context),
            errorMessage = null
        )
    }

    fun refreshDisplays() {
        viewModelScope.launch { refreshDisplaysInternal() }
    }

    fun setInnerDpi(dpi: Int) {
        viewModelScope.launch {
            preferences.setInnerDpi(dpi)
            _uiState.value = _uiState.value.copy(innerDpi = dpi)
        }
    }

    fun setOuterDpi(dpi: Int) {
        viewModelScope.launch {
            preferences.setOuterDpi(dpi)
            _uiState.value = _uiState.value.copy(outerDpi = dpi)
        }
    }

    fun applyInnerDensity() {
        viewModelScope.launch {
            applyDensity(
                targetDpi = _uiState.value.innerDpi,
                isInner = true,
                successMessage = { "Внутренний экран: установлено $it DPI" },
                bypassMessage = { "Внутренний экран: уже $it DPI" },
                wrongScreenMessage = "Для применения настроек внутреннего экрана раскройте телефон"
            )
        }
    }

    fun applyOuterDensity() {
        viewModelScope.launch {
            applyDensity(
                targetDpi = _uiState.value.outerDpi,
                isInner = false,
                successMessage = { "Внешний экран: установлено $it DPI" },
                bypassMessage = { "Внешний экран: уже $it DPI" },
                wrongScreenMessage = "Для применения настроек внешнего экрана закройте телефон"
            )
        }
    }

    private suspend fun applyDensity(
        targetDpi: Int,
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

        val freshDpiResult = ShizukuManager.getCurrentDensity()
        if (freshDpiResult.isFailure) {
            _uiState.value = state.copy(
                isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
                errorMessage = freshDpiResult.exceptionOrNull()?.message
                    ?: "Не удалось связаться с Shizuku",
                isLoading = false
            )
            return
        }

        val freshDpi = freshDpiResult.getOrNull()
        if (freshDpi != null && freshDpi == targetDpi) {
            _uiState.value = state.copy(
                currentDensity = targetDpi,
                currentDp = DisplayUtils.dpiToDp(targetDpi, active.shortPx),
                successMessage = bypassMessage(targetDpi),
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
                    currentDp = DisplayUtils.dpiToDp(targetDpi, active.shortPx),
                    successMessage = successMessage(targetDpi),
                    isLoading = false
                )
            },
            onFailure = {
                _uiState.value = _uiState.value.copy(
                    errorMessage = it.message ?: "Не удалось применить DPI",
                    isLoading = false
                )
            }
        )
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            errorMessage = null,
            successMessage = null
        )
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        super.onCleared()
    }

    fun requestShizukuPermission() {
        try {
            if (Shizuku.getUid() != -1) {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
