package com.densityfold.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.densityfold.data.DensityPreferences
import com.densityfold.data.DisplayInfo
import com.densityfold.shizuku.ShizukuManager
import com.densityfold.utils.DisplayUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val activeDisplay: DisplayInfo? = null,
    val internalDisplay: DisplayInfo? = null,
    val externalDisplay: DisplayInfo? = null,
    val currentDensity: Int = 0,
    val targetDensity: Int = 0,
    val innerDpi: Int = 360,
    val outerDpi: Int = 411,
    val isShizukuAvailable: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = DensityPreferences(application)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        refreshDisplays()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                innerDpi = preferences.getInnerDpi(),
                outerDpi = preferences.getOuterDpi(),
                isShizukuAvailable = ShizukuManager.isShizukuAvailable()
            )
        }
    }

    fun refreshDisplays() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            val (internal, external) = DisplayUtils.identifyFoldDisplays(context)
            val active = internal // Упрощение: считаем активным внутренний по умолчанию, можно доработать
            
            _uiState.value = _uiState.value.copy(
                activeDisplay = active,
                internalDisplay = internal,
                externalDisplay = external,
                currentDensity = active?.density ?: 0,
                targetDensity = if (active == internal) _uiState.value.innerDpi else _uiState.value.outerDpi
            )
        }
    }

    fun setInnerDpi(dpi: Int) { viewModelScope.launch { preferences.setInnerDpi(dpi); _uiState.value = _uiState.value.copy(innerDpi = dpi) } }
    fun setOuterDpi(dpi: Int) { viewModelScope.launch { preferences.setOuterDpi(dpi); _uiState.value = _uiState.value.copy(outerDpi = dpi) } }

    fun applyCurrentScreenDensity() {
        viewModelScope.launch {
            val state = _uiState.value
            val display = state.activeDisplay ?: run {
                _uiState.value = state.copy(errorMessage = "Дисплей не найден")
                return@launch
            }
            val targetDpi = if (display == state.internalDisplay) state.innerDpi else state.outerDpi
            
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            val result = ShizukuManager.setDensity(display.displayId, targetDpi)
            
            result.fold(
                onSuccess = { _uiState.value = _uiState.value.copy(currentDensity = targetDpi, successMessage = it, isLoading = false) },
                onFailure = { _uiState.value = _uiState.value.copy(errorMessage = it.message, isLoading = false) }
            )
        }
    }
}
