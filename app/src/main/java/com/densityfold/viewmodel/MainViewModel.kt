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
    val allDisplays: List<DisplayInfo> = emptyList(),
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
    
    // Флаги инициализации (отслеживают, были ли сохранены значения)
    private var innerDpiInitialized = false
    private var outerDpiInitialized = false

    init {
        // Сначала загружаем настройки
        loadSettings()
        // Затем обновляем информацию о дисплеях (но не перезаписываем настройки)
        refreshDisplays()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val inner = preferences.getInnerDpi()
            val outer = preferences.getOuterDpi()
            
            // Проверяем, были ли значения реально сохранены
            val hasInner = preferences.hasInnerDpi()
            val hasOuter = preferences.hasOuterDpi()
            
            innerDpiInitialized = hasInner
            outerDpiInitialized = hasOuter
            
            _uiState.value = _uiState.value.copy(
                innerDpi = inner,
                outerDpi = outer,
                isShizukuAvailable = ShizukuManager.isShizukuAvailable()
            )
        }
    }

    fun refreshDisplays() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            val active = DisplayUtils.getActiveDisplay(context)
            val all = DisplayUtils.getAllDisplays(context)
            
            val currentDpi = ShizukuManager.getCurrentDensity().getOrNull() ?: 0
            
            _uiState.value = _uiState.value.copy(
                activeDisplay = active,
                allDisplays = all,
                currentDensity = currentDpi,
                isShizukuAvailable = ShizukuManager.isShizukuAvailable(),
                diagnosticInfo = DisplayUtils.getDiagnosticString(context),
                errorMessage = null
            )
            
            // ТОЛЬКО при первом запуске (если ключи не существовали) 
            // инициализируем значения текущим DPI системы
            if (!innerDpiInitialized && currentDpi > 0 && active?.isExternal == false) {
                preferences.setInnerDpi(currentDpi)
                innerDpiInitialized = true
                _uiState.value = _uiState.value.copy(innerDpi = currentDpi)
            }
            
            if (!outerDpiInitialized && currentDpi > 0 && active?.isExternal == true) {
                preferences.setOuterDpi(currentDpi)
                outerDpiInitialized = true
                _uiState.value = _uiState.value.copy(outerDpi = currentDpi)
            }
        }
    }

    fun setInnerDpi(dpi: Int) { 
        viewModelScope.launch { 
            preferences.setInnerDpi(dpi)
            innerDpiInitialized = true
            _uiState.value = _uiState.value.copy(innerDpi = dpi) 
        } 
    }
    
    fun setOuterDpi(dpi: Int) { 
        viewModelScope.launch { 
            preferences.setOuterDpi(dpi)
            outerDpiInitialized = true
            _uiState.value = _uiState.value.copy(outerDpi = dpi) 
        } 
    }

    fun applyCurrentScreenDensity() {
        viewModelScope.launch {
            val state = _uiState.value
            val display = state.activeDisplay ?: run {
                _uiState.value = state.copy(errorMessage = "Активный дисплей не найден")
                return@launch
            }
            
            val targetDpi = if (display.isExternal) state.outerDpi else state.innerDpi
            
            _uiState.value = state.copy(isLoading = true, errorMessage = null, successMessage = null)
            val result = ShizukuManager.setDensity(targetDpi)
            
            result.fold(
                onSuccess = { 
                    _uiState.value = _uiState.value.copy(
                        currentDensity = targetDpi, 
                        successMessage = "${if (display.isExternal) "Внешний" else "Внутренний"} экран: $targetDpi dpi",
                        isLoading = false
                    ) 
                },
                onFailure = { _uiState.value = _uiState.value.copy(errorMessage = it.message, isLoading = false) }
            )
        }
    }
    
    fun requestShizukuPermission() {
        ShizukuManager.requestPermission(1001)
    }
}
