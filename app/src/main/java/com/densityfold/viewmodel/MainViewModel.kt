package com.densityfold.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.densityfold.FoldMonitorService
import com.densityfold.backend.DensityBackendManager
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.DisplayInfo
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.DensityExecutor
import com.densityfold.engine.DensityProfile
import com.densityfold.backend.shizuku.ShizukuManager
import com.densityfold.backend.shizuku.ShizukuMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class UiState(
    val activeDisplay: DisplayInfo? = null,
    val innerDpi: Int = 350,
    val outerDpi: Int = 440,
    val innerShortPx: Int = DisplayUtils.FALLBACK_INNER_SHORT_PX,
    val outerShortPx: Int = DisplayUtils.FALLBACK_OUTER_SHORT_PX,
    val innerSizeEstimated: Boolean = true,
    val outerSizeEstimated: Boolean = true,
    val isFoldMonitorEnabled: Boolean = false,
    val isAutoStartEnabled: Boolean = false,
    val isRootBackendEnabled: Boolean = false,
    val isShizukuBackendEnabled: Boolean = true,
    val isShizukuAvailable: Boolean = false,
    val activeBackendName: String? = null,
    val isBackendReady: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isOverlayPermissionGranted: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = DensityPreferences(application)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val dpiSaveJobs = mutableMapOf<DensityProfile, Job>()
    private val activeDensityOperations =
        java.util.concurrent.atomic.AtomicInteger(0)
    private companion object {
        const val DPI_SAVE_DEBOUNCE_MS = 300L
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }

    @Volatile
    private var pendingShizukuPermissionRequest = false

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                pendingShizukuPermissionRequest = false

                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    viewModelScope.launch {
                        refreshBackendSelection()
                    }
                } else {
                    _uiState.update {
                        it.copy(errorMessage = "Разрешение Shizuku не выдано")
                    }
                }
            }
        }

    private val shizukuBinderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            if (pendingShizukuPermissionRequest) {
                requestShizukuPermissionIfReady()
            }
            viewModelScope.launch {
                refreshDisplaysInternal()
            }
        }

    private val shizukuBinderDeadListener =
        Shizuku.OnBinderDeadListener {
            _uiState.update {
                it.copy(
                    isShizukuAvailable = false,
                    isBackendReady = if (it.activeBackendName == "Shizuku") {
                        false
                    } else {
                        it.isBackendReady
                    },
                    isLoading = activeDensityOperations.get() > 0,
                    errorMessage = "Shizuku отключён. Автоприменение приостановлено."
                )
            }
        }

    init {
        viewModelScope.launch {
            DensityBackendManager.selectedBackend.collect { backend ->
                val shizukuReady = ShizukuMonitor.runtimeState.value?.let {
                    it.mainBinder && it.permission && it.userService
                } ?: false

                _uiState.update {
                    it.copy(
                        activeBackendName = when (backend?.name) {
                            "ROOT" -> "Root"
                            "SHIZUKU" -> "Shizuku"
                            else -> null
                        },
                        isBackendReady = when (backend?.name) {
                            "ROOT" -> true
                            "SHIZUKU" -> shizukuReady
                            else -> false
                        }
                    )
                }
            }
        }


        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)

        viewModelScope.launch {
            ShizukuMonitor.runtimeState.collect { state ->
                val ready = state?.let {
                    it.mainBinder && it.permission && it.userService
                } ?: false

                _uiState.update {
                    it.copy(
                        isShizukuAvailable = ready,
                        isBackendReady = if (it.activeBackendName == "Shizuku") {
                            ready
                        } else {
                            it.isBackendReady
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            loadSettingsInternal()
            refreshDisplaysInternal()
        }
    }

    private suspend fun loadSettingsInternal() {
        val settings = preferences.getSettings()

        _uiState.update {
            it.copy(
                innerDpi = settings.innerDpi,
                outerDpi = settings.outerDpi,
                isFoldMonitorEnabled = settings.foldMonitorEnabled,
                isAutoStartEnabled = settings.autoStartEnabled,
                isRootBackendEnabled = settings.rootBackendEnabled,
                isShizukuBackendEnabled = settings.shizukuBackendEnabled,
            )
        }

        updateFoldMonitorService(settings.foldMonitorEnabled)
    }

    private fun updateFoldMonitorService(enabled: Boolean) {
        val intent = Intent(getApplication(), FoldMonitorService::class.java)
        if (enabled) {
            androidx.core.content.ContextCompat.startForegroundService(
                getApplication<Application>(),
                intent
            )
        } else {
            getApplication<Application>().stopService(intent)
        }
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

    fun setRootBackendEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val updated = preferences.setRootBackendEnabled(enabled)
            if (updated) {
                _uiState.update { it.copy(isRootBackendEnabled = enabled) }
                refreshBackendSelection()
            } else {
                _uiState.update {
                    it.copy(errorMessage = "Нельзя отключить оба способа выполнения")
                }
            }
        }
    }

    fun setShizukuBackendEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val updated = preferences.setShizukuBackendEnabled(enabled)

            if (!updated) {
                _uiState.update {
                    it.copy(errorMessage = "Нельзя отключить оба способа выполнения")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isShizukuBackendEnabled = enabled,
                    errorMessage = null
                )
            }

            if (enabled) {
                pendingShizukuPermissionRequest = true
                try {
                    ShizukuManager.start()
                    requestShizukuPermissionIfReady()
                } catch (e: Exception) {
                    pendingShizukuPermissionRequest = false
                    _uiState.update {
                        it.copy(errorMessage = "Не удалось запустить Shizuku: ${e.message}")
                    }
                }
            } else {
                pendingShizukuPermissionRequest = false
                refreshBackendSelection()
            }
        }
    }

    private suspend fun refreshBackendSelection() {
        try {
            val settings = preferences.getSettings()

            DensityBackendManager.selectBackend(
                rootEnabled = settings.rootBackendEnabled,
                shizukuEnabled = settings.shizukuBackendEnabled
            ).onSuccess {
                _uiState.update { it.copy(errorMessage = null) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        errorMessage = "Не удалось выбрать способ выполнения: " +
                            (error.message ?: "backend недоступен")
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    errorMessage = "Ошибка переключения backend: " +
                        (e.message ?: "неизвестная ошибка")
                )
            }
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
                innerSizeEstimated = active == null || active.isExternal,
                outerSizeEstimated = active == null || !active.isExternal,
                isOverlayPermissionGranted = Settings.canDrawOverlays(context)
            )
        }
    }

    fun refreshDisplays() {
        viewModelScope.launch { refreshDisplaysInternal() }
    }

    fun setInnerDpi(dpi: Int) = setDpi(DensityProfile.INNER, dpi)

    fun setOuterDpi(dpi: Int) = setDpi(DensityProfile.OUTER, dpi)

    private fun setDpi(profile: DensityProfile, dpi: Int) {
        val value = dpi.coerceIn(
            DensityPreferences.MIN_DPI,
            DensityPreferences.MAX_DPI
        )

        _uiState.update {
            when (profile) {
                DensityProfile.INNER -> it.copy(innerDpi = value)
                DensityProfile.OUTER -> it.copy(outerDpi = value)
            }
        }

        scheduleDpiSave(profile, value)
    }

    private fun scheduleDpiSave(profile: DensityProfile, dpi: Int) {
        dpiSaveJobs[profile]?.cancel()
        dpiSaveJobs[profile] = viewModelScope.launch {
            delay(DPI_SAVE_DEBOUNCE_MS)
            saveDpiPreference(profile, dpi)
        }
    }

    private suspend fun saveDpiPreference(profile: DensityProfile, dpi: Int) {
        when (profile) {
            DensityProfile.INNER -> preferences.setInnerDpi(dpi)
            DensityProfile.OUTER -> preferences.setOuterDpi(dpi)
        }
    }

    private suspend fun flushDpiPreference(profile: DensityProfile) {
        dpiSaveJobs.remove(profile)?.cancelAndJoin()
        val dpi = when (profile) {
            DensityProfile.INNER -> _uiState.value.innerDpi
            DensityProfile.OUTER -> _uiState.value.outerDpi
        }
        saveDpiPreference(profile, dpi)
    }

    fun applyInnerDensity() {
        viewModelScope.launch { applyDensity(DensityProfile.INNER) }
    }

    fun applyOuterDensity() {
        viewModelScope.launch { applyDensity(DensityProfile.OUTER) }
    }

    private suspend fun applyDensity(profile: DensityProfile) {
        val context = getApplication<Application>().applicationContext
        activeDensityOperations.incrementAndGet()

        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                successMessage = null
            )
        }

        try {
            flushDpiPreference(profile)

            val result = DensityExecutor.apply(
                context = context,
                profile = profile
            )

            val applied = result.getOrThrow()
            val screenName = if (profile == DensityProfile.INNER) {
                "Внутренний"
            } else {
                "Внешний"
            }
            val status = if (applied.alreadyApplied) "уже" else "установлено"

            _uiState.update {
                it.copy(
                    successMessage = "$screenName экран: $status ${applied.targetDpi} DPI",
                    errorMessage = null
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    errorMessage = e.message ?: "Не удалось применить DPI"
                )
            }
        } finally {
            val stillLoading = activeDensityOperations.decrementAndGet() > 0
            _uiState.update { it.copy(isLoading = stillLoading) }
        }
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
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        super.onCleared()
    }

    fun requestShizukuPermission() {
        pendingShizukuPermissionRequest = true
        try {
            ShizukuManager.start()
            requestShizukuPermissionIfReady()
        } catch (e: Exception) {
            pendingShizukuPermissionRequest = false
            Log.w("DensityFold-UI", "Не удалось запустить Shizuku", e)
        }
    }

    private fun requestShizukuPermissionIfReady() {
        if (!pendingShizukuPermissionRequest) return
        if (!Shizuku.pingBinder()) return

        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                pendingShizukuPermissionRequest = false
                viewModelScope.launch {
                    refreshBackendSelection()
                }
            } else {
                pendingShizukuPermissionRequest = false
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            pendingShizukuPermissionRequest = false
            Log.w("DensityFold-UI", "Не удалось запросить разрешение Shizuku", e)
            _uiState.update {
                it.copy(errorMessage = "Ошибка запроса разрешения Shizuku: ${e.message}")
            }
        }
    }
}
