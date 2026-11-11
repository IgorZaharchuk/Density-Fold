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
import com.densityfold.engine.DensityExecutor
import com.densityfold.engine.DensityProfile
import com.densityfold.engine.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class UiState(
    val activeDisplay: DisplayInfo? = null,
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
        _uiState.update {
            it.copy(
                isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
                errorMessage = null
            )
        }
        viewModelScope.launch { refreshDisplaysInternal() }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        _uiState.update {
            it.copy(
                isShizukuAvailable = false,
                isLoading = false,
                errorMessage = "Shizuku отключён. Автоприменение приостановлено."
            )
        }
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
        val active = DisplayUtils.getActiveDisplay(getApplication())
        val innerShortPx = DisplayUtils.getInnerShortPx(active)
        val outerShortPx = DisplayUtils.getOuterShortPx(active)
        val innerDpi = preferences.getInnerDpi()
        val outerDpi = preferences.getOuterDpi()
        val monitorEnabled = preferences.isFoldMonitorEnabled()
        val autoStartEnabled = preferences.isAutoStartEnabled()

        _uiState.update {
            it.copy(
                innerDpi = innerDpi,
                outerDpi = outerDpi,
                innerShortPx = innerShortPx,
                outerShortPx = outerShortPx,
                isFoldMonitorEnabled = monitorEnabled,
                isAutoStartEnabled = autoStartEnabled,
                isShizukuAvailable = ShizukuManager.isShizukuAvailable()
            )
        }

        updateFoldMonitorService(monitorEnabled)
    }

    private fun updateFoldMonitorService(enabled: Boolean) {
        val intent = Intent(getApplication(), FoldMonitorService::class.java)
        if (enabled) getApplication<Application>().startService(intent)
        else getApplication<Application>().stopService(intent)
    }

    fun setFoldMonitorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setFoldMonitorEnabled(enabled)
            _uiState.update { it.copy(isFoldMonitorEnabled = enabled) }
            updateFoldMonitorService(enabled)
        }
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoStartEnabled(enabled)
            _uiState.update { it.copy(isAutoStartEnabled = enabled) }
        }
    }

    private suspend fun refreshDisplaysInternal() {
        val context = getApplication<Application>().applicationContext
        val active = DisplayUtils.getActiveDisplay(context)

        _uiState.update {
            it.copy(
                activeDisplay = active,
                innerShortPx = DisplayUtils.getInnerShortPx(active),
                outerShortPx = DisplayUtils.getOuterShortPx(active),
                isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
                isOverlayPermissionGranted = Settings.canDrawOverlays(context)
            )
        }
    }

    fun refreshDisplays() {
        viewModelScope.launch { refreshDisplaysInternal() }
    }

    fun setInnerDpi(dpi: Int) {
        viewModelScope.launch {
            preferences.setInnerDpi(dpi)
            _uiState.update { it.copy(innerDpi = dpi) }
        }
    }

    fun setOuterDpi(dpi: Int) {
        viewModelScope.launch {
            preferences.setOuterDpi(dpi)
            _uiState.update { it.copy(outerDpi = dpi) }
        }
    }

    fun applyInnerDensity() {
        viewModelScope.launch {
            applyDensity(
                profile = DensityProfile.INNER,
                successMessage = { "Внутренний экран: установлено $it DPI" },
                bypassMessage = { "Внутренний экран: уже $it DPI" },
            )
        }
    }

    fun applyOuterDensity() {
        viewModelScope.launch {
            applyDensity(
                profile = DensityProfile.OUTER,
                successMessage = { "Внешний экран: установлено $it DPI" },
                bypassMessage = { "Внешний экран: уже $it DPI" },
            )
        }
    }

    private suspend fun applyDensity(
        profile: DensityProfile,
        successMessage: (Int) -> String,
        bypassMessage: (Int) -> String
    ) {
        val context = getApplication<Application>().applicationContext

        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                successMessage = null
            )
        }

        val result = DensityExecutor.apply(
            context = context,
            profile = profile
        )

        result.fold(
            onSuccess = { applied ->
                val message = if (applied.alreadyApplied) {
                    bypassMessage(applied.targetDpi)
                } else {
                    successMessage(applied.targetDpi)
                }

                _uiState.update {
                    it.copy(
                        successMessage = message,
                        isLoading = false
                    )
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
                        errorMessage = error.message ?: "Не удалось применить DPI",
                        isLoading = false
                    )
                }
            }
        )
    }

    fun clearMessages() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                successMessage = null
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
            if (Shizuku.getUid() != -1) {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
