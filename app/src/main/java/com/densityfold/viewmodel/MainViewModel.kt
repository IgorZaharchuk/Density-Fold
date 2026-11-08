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
    val currentDp: Int = 0,  // Логическое значение (то что видит пользователь)
    val innerDp: Int = 411,   // Целевое для внутреннего (в dp)
    val outerDp: Int = 411,   // Целевое для внешнего (в dp)
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
        val inner = preferences.getInnerDp()
        val outer = preferences.getOuterDp()
        _uiState.value = _uiState.value.copy(
            innerDp = inner,
            outerDp = outer,
            isShizukuAvailable = ShizukuManager.isShizukuAvailable()
        )
    }

    private suspend fun refreshDisplaysInternal() {
        val context = getApplication<Application>().applicationContext
        val active = DisplayUtils.getActiveDisplay(context)
        val currentDpi = ShizukuManager.getCurrentDensity().getOrNull() ?: 0
        
        // Конвертируем dpi → dp для отображения
        val currentDp = active?.let { 
            val minPx = minOf(it.width, it.height)
            (minPx * 160f / currentDpi).toInt()
        } ?: 0

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

    // Применение ВНУТРЕННЕГО DPI (конвертируем dp → dpi)
    fun applyInnerDensity() {
        viewModelScope.launch {
            val state = _uiState.value
            val display = state.activeDisplay
            
            if (display?.isExternal == true) {
                _uiState.value = state.copy(
                    errorMessage = "Для применения настроек внутреннего экрана, пожалуйста, раскройте телефон."
                )
                return@launch
            }
            
            // Конвертируем dp → dpi для команды wm density
            val minPx = minOf(display?.width ?: 1080, display?.height ?: 2184)
            val targetDpi = (minPx * 160f / state.innerDp).toInt()
            
            _uiState.value = state.copy(isLoading = true, errorMessage = null, successMessage = null)
            val result = ShizukuManager.setDensity(targetDpi)
            
            result.fold(
                onSuccess = { 
                    _uiState.value = _uiState.value.copy(
                        currentDensity = targetDpi,
                        currentDp = state.innerDp,
                        successMessage = "Внутренний экран: ${state.innerDp} dp",
                        isLoading = false
                    ) 
                },
                onFailure = { _uiState.value = _uiState.value.copy(errorMessage = it.message, isLoading = false) }
            )
        }
    }

    // Применение ВНЕШНЕГО DPI (конвертируем dp → dpi)
    fun applyOuterDensity() {
        viewModelScope.launch {
            val state = _uiState.value
            val display = state.activeDisplay
            
            if (display?.isExternal == false) {
                _uiState.value = state.copy(
                    errorMessage = "Для применения настроек внешнего экрана, пожалуйста, закройте телефон."
                )
                return@launch
            }
            
            // Конвертируем dp → dpi для команды wm density
            val minPx = minOf(display?.width ?: 1080, display?.height ?: 2520)
            val targetDpi = (minPx * 160f / state.outerDp).toInt()
            
            _uiState.value = state.copy(isLoading = true, errorMessage = null, successMessage = null)
            val result = ShizukuManager.setDensity(targetDpi)
            
            result.fold(
                onSuccess = { 
                    _uiState.value = _uiState.value.copy(
                        currentDensity = targetDpi,
                        currentDp = state.outerDp,
                        successMessage = "Внешний экран: ${state.outerDp} dp",
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
