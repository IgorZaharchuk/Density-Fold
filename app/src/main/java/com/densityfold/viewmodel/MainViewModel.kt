package com.densityfold.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.densityfold.FoldMonitorService
import com.densityfold.backend.shizuku.ShizukuPermissionGrant
import com.densityfold.data.DensityPreferences
import com.densityfold.engine.DisplayInfo
import com.densityfold.engine.DisplayUtils
import com.densityfold.engine.DensityExecutor
import com.densityfold.engine.DensityProfile
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
    val previousInnerDpi: Int? = null,
    val previousOuterDpi: Int? = null,
    val innerShortPx: Int = DisplayUtils.FALLBACK_INNER_SHORT_PX,
    val outerShortPx: Int = DisplayUtils.FALLBACK_OUTER_SHORT_PX,
    val innerSizeEstimated: Boolean = true,
    val outerSizeEstimated: Boolean = true,
    val isFoldMonitorEnabled: Boolean = false,
    val isAutoStartEnabled: Boolean = false,
    val isWriteSecureSettingsGranted: Boolean = false,
    val isPermissionGrantInProgress: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
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
                    grantWriteSecureSettings()
                } else {
                    _uiState.update {
                        it.copy(
                            isPermissionGrantInProgress = false,
                            errorMessage = "Разрешение Shizuku не выдано"
                        )
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

    init {
        viewModelScope.launch {
            preferences.observePreviousDpi().collect {
                (previousInnerDpi, previousOuterDpi) ->
                _uiState.update {
                    it.copy(
                        previousInnerDpi = previousInnerDpi,
                        previousOuterDpi = previousOuterDpi
                    )
                }
            }
        }

        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)

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
                isAutoStartEnabled = settings.autoStartEnabled
            )
        }

        updateFoldMonitorService(settings.foldMonitorEnabled)
        refreshWriteSecureSettingsPermission()
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
            _uiState.update {
                it.copy(
                    isFoldMonitorEnabled = enabled,
                    errorMessage = null
                )
            }
            updateFoldMonitorService(enabled)
        }
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoStartEnabled(enabled)
            _uiState.update { it.copy(isAutoStartEnabled = enabled) }
        }
    }

    fun refreshWriteSecureSettingsPermission() {
        val granted = getApplication<Application>().checkSelfPermission(
            "android.permission.WRITE_SECURE_SETTINGS"
        ) == PackageManager.PERMISSION_GRANTED

        _uiState.update {
            it.copy(isWriteSecureSettingsGranted = granted)
        }
    }

    fun requestWriteSecureSettings() {
        if (_uiState.value.isPermissionGrantInProgress) return

        refreshWriteSecureSettingsPermission()
        if (_uiState.value.isWriteSecureSettingsGranted) return

        _uiState.update {
            it.copy(
                isPermissionGrantInProgress = true,
                errorMessage = null
            )
        }

        try {
            if (!Shizuku.pingBinder()) {
                pendingShizukuPermissionRequest = false
                _uiState.update {
                    it.copy(
                        isPermissionGrantInProgress = false,
                        errorMessage = "Сначала запустите Shizuku"
                    )
                }
                return
            }

            if (Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
            ) {
                grantWriteSecureSettings()
            } else {
                pendingShizukuPermissionRequest = true
                requestShizukuPermissionIfReady()
            }
        } catch (e: Exception) {
            pendingShizukuPermissionRequest = false
            _uiState.update {
                it.copy(
                    isPermissionGrantInProgress = false,
                    errorMessage = "Ошибка Shizuku: ${e.message}"
                )
            }
        }
    }

    private fun grantWriteSecureSettings() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            ShizukuPermissionGrant.grant(context)
                .onSuccess {
                    refreshWriteSecureSettingsPermission()
                    _uiState.update {
                        it.copy(
                            isPermissionGrantInProgress = false,
                            errorMessage = if (it.isWriteSecureSettingsGranted) {
                                null
                            } else {
                                "WRITE_SECURE_SETTINGS не выдано"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    refreshWriteSecureSettingsPermission()
                    _uiState.update {
                        it.copy(
                            isPermissionGrantInProgress = false,
                            errorMessage = error.message
                                ?: "Не удалось выдать разрешение"
                        )
                    }
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
                isWriteSecureSettingsGranted =
                    context.checkSelfPermission(
                        "android.permission.WRITE_SECURE_SETTINGS"
                    ) == PackageManager.PERMISSION_GRANTED
            )
        }
    }

    fun refreshDisplays() {
        viewModelScope.launch { refreshDisplaysInternal() }
    }

    fun setInnerDpi(dpi: Int) {
        setDpi(DensityProfile.INNER, dpi)
    }

    fun setOuterDpi(dpi: Int) {
        setDpi(DensityProfile.OUTER, dpi)
    }

    private fun setDpi(profile: DensityProfile, dpi: Int): Boolean {
        val state = _uiState.value
        val shortPx = when (profile) {
            DensityProfile.INNER -> state.innerShortPx
            DensityProfile.OUTER -> state.outerShortPx
        }
        val allowedRange = DisplayUtils.allowedDpiRange(shortPx)

        if (dpi !in allowedRange) {
            _uiState.update {
                it.copy(
                    errorMessage = "Допустимый диапазон для этого экрана: " +
                        "${allowedRange.first}–${allowedRange.last} DPI"
                )
            }
            return false
        }

        _uiState.update {
            when (profile) {
                DensityProfile.INNER -> it.copy(innerDpi = dpi, errorMessage = null)
                DensityProfile.OUTER -> it.copy(outerDpi = dpi, errorMessage = null)
            }
        }

        scheduleDpiSave(profile, dpi)
        return true
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
        viewModelScope.launch {
            applyDensity(DensityProfile.INNER, armPreviousDpi = true)
        }
    }

    fun applyOuterDensity() {
        viewModelScope.launch {
            applyDensity(DensityProfile.OUTER, armPreviousDpi = true)
        }
    }

    fun restorePreviousInnerDensity() = restorePreviousDensity(DensityProfile.INNER)

    fun restorePreviousOuterDensity() = restorePreviousDensity(DensityProfile.OUTER)

    private fun restorePreviousDensity(profile: DensityProfile) {
        if (_uiState.value.isLoading) return
        val previous = when (profile) {
            DensityProfile.INNER -> _uiState.value.previousInnerDpi
            DensityProfile.OUTER -> _uiState.value.previousOuterDpi
        } ?: return

        if (!setDpi(profile, previous)) return
        viewModelScope.launch { applyDensity(profile) }
    }

    private suspend fun applyDensity(
        profile: DensityProfile,
        armPreviousDpi: Boolean = false
    ) {
        val context = getApplication<Application>().applicationContext

        if (context.checkSelfPermission(
                "android.permission.WRITE_SECURE_SETTINGS"
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _uiState.update {
                it.copy(
                    isWriteSecureSettingsGranted = false,
                    errorMessage = "Нет разрешения WRITE_SECURE_SETTINGS"
                )
            }
            return
        }

        activeDensityOperations.incrementAndGet()

        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                successMessage = null
            )
        }

        try {
            if (armPreviousDpi) {
                preferences.armPreviousDpi(profile == DensityProfile.OUTER)
            }
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
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        super.onCleared()
    }

    private fun requestShizukuPermissionIfReady() {
        if (!pendingShizukuPermissionRequest) return

        try {
            if (!Shizuku.pingBinder()) return

            if (Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingShizukuPermissionRequest = false
                grantWriteSecureSettings()
            } else {
                pendingShizukuPermissionRequest = false
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            pendingShizukuPermissionRequest = false
            _uiState.update {
                it.copy(
                    isPermissionGrantInProgress = false,
                    errorMessage = "Ошибка запроса разрешения Shizuku: ${e.message}"
                )
            }
        }
    }
}
