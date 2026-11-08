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
    val currentDp: Int = 0,
    val innerDp: Int = 900,
    val outerDp: Int = 411,
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
        
        val currentDp = if (currentDpi > 0 && active != null) {
            DisplayUtils.dpiToDp(currentDpi, active.shortPx)
        } else { 0 }

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
            val state = _uiState.value
            val context = getApplication<Application>().applicationContext
            val active = DisplayUtils.getActiveDisplay(context)
            
            if (active == null) {
                _uiState.value = state.copy(errorMessage = "Не удалось определить активный экран")
                return@launch
            }
            if (active.isExternal) {
                _uiState.value = state.copy(errorMessage = "Для применения настроек внутреннего экрана раскройте телефон")
                return@launch
            }
            
            val targetDpi = DisplayUtils.dpToDpi(state.innerDp, active.shortPx)
            if (targetDpi == null) {
                _uiState.value = state.copy(errorMessage = "Некорректные параметры для расчёта DPI")
                return@launch
            }
            
            // БАЙПАСС: если текущий DPI уже равен целевому — не выполняем команду
            if (state.currentDensity == targetDpi) {
                _uiState.value = state.copy(
                    successMessage = "Внутренний экран: уже ${state.innerDp} dp (без изменений)",
                    isLoading = false
                )
                return@launch
            }
            
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

    fun applyOuterDensity() {
        viewModelScope.launch {
            val state = _uiState.value
            val context = getApplication<Application>().applicationContext
            val active = DisplayUtils.getActiveDisplay(context)
            
            if (active == null) {
                _uiState.value = state.copy(errorMessage = "Не удалось определить активный экран")
                return@launch
            }
            if (!active.isExternal) {
                _uiState.value = state.copy(errorMessage = "Для применения настроек внешнего экрана закройте телефон")
                return@launch
            }
            
            val targetDpi = DisplayUtils.dpToDpi(state.outerDp, active.shortPx)
            if (targetDpi == null) {
                _uiState.value = state.copy(errorMessage = "Некорректные параметры для расчёта DPI")
                return@launch
            }
            
            // БАЙПАСС: если текущий DPI уже равен целевому — не выполняем команду
            if (state.currentDensity == targetDpi) {
                _uiState.value = state.copy(
                    successMessage = "Внешний экран: уже ${state.outerDp} dp (без изменений)",
                    isLoading = false
                )
                return@launch
            }
            
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
