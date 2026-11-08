package com.densityfold.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val innerDpi: Int = 360,
    val outerDpi: Int = 411,
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
        val inner = preferences.getInnerDpi()
        val outer = preferences.getOuterDpi()
        _uiState.value = _uiState.value.copy(
            innerDpi = inner,
            outerDpi = outer,
            isShizukuAvailable = ShizukuManager.isShizukuAvailable()
        )
    }

    private suspend fun refreshDisplaysInternal() {
        val context = getApplication<Application>().applicationContext
        val active = DisplayUtils.getActiveDisplay(context)
        val currentDpi = ShizukuManager.getCurrentDensity().getOrNull() ?: 0

        _uiState.value = _uiState.value.copy(
            activeDisplay = active,
            currentDensity = currentDpi,
            isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
            diagnosticInfo = DisplayUtils.getDiagnosticString(context),
            errorMessage = null
        )
    }

    fun refreshDisplays() { viewModelScope.launch { refreshDisplaysInternal() } }

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

    // Применение ВНУТРЕННЕГО DPI (работает только если мы сейчас на внутреннем экране)
    fun applyInnerDensity() {
        viewModelScope.launch {
            val state = _uiState.value
            val display = state.activeDisplay
            
            // Защита: применяем только если текущий экран НЕ внешний
            if (display?.isExternal == true) {
                _uiState.value = state.copy(
                    errorMessage = "Для применения настроек внутреннего экрана, пожалуйста, раскройте телефон."
                )
                return@launch
            }
            
            _uiState.value = state.copy(isLoading = true, errorMessage = null, successMessage = null)
            val result = ShizukuManager.setDensity(state.innerDpi)
            
            result.fold(
                onSuccess = { 
                    _uiState.value = _uiState.value.copy(
                        currentDensity = state.innerDpi, 
                        successMessage = "Внутренний экран: ${state.innerDpi} dpi",
                        isLoading = false
                    ) 
                },
                onFailure = { _uiState.value = _uiState.value.copy(errorMessage = it.message, isLoading = false) }
            )
        }
    }

    // Применение ВНЕШНЕГО DPI (работает только если мы сейчас на внешнем экране)
    fun applyOuterDensity() {
        viewModelScope.launch {
            val state = _uiState.value
            val display = state.activeDisplay
            
            // Защита: применяем только если текущий экран внешний
            if (display?.isExternal == false) {
                _uiState.value = state.copy(
                    errorMessage = "Для применения настроек внешнего экрана, пожалуйста, закройте телефон."
                )
                return@launch
            }
            
            _uiState.value = state.copy(isLoading = true, errorMessage = null, successMessage = null)
            val result = ShizukuManager.setDensity(state.outerDpi)
            
            result.fold(
                onSuccess = { 
                    _uiState.value = _uiState.value.copy(
                        currentDensity = state.outerDpi, 
                        successMessage = "Внешний экран: ${state.outerDpi} dpi",
                        isLoading = false
                    ) 
                },
                onFailure = { _uiState.value = _uiState.value.copy(errorMessage = it.message, isLoading = false) }
            )
        }
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
