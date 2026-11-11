package com.densityfold

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.densityfold.ui.theme.DensityFoldTheme
import com.densityfold.viewmodel.MainViewModel
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> }
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        setContent {
            DensityFoldTheme {
                MainScreen(
                    viewModel = mainViewModel,
                    onRequestOverlayPermission = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mainViewModel.isInitialized) mainViewModel.refreshDisplays()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::mainViewModel.isInitialized) mainViewModel.refreshDisplays()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestOverlayPermission: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var automationExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.errorMessage, uiState.successMessage) {
        if (uiState.errorMessage != null || uiState.successMessage != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearMessages()
        }
    }

    val innerMin = (com.densityfold.engine.DisplayUtils.dpToDpi(1200, uiState.innerShortPx) ?: 72)
        .coerceAtLeast(72)
    val innerMax = (com.densityfold.engine.DisplayUtils.dpToDpi(200, uiState.innerShortPx) ?: 2000)
        .coerceAtMost(2000)
    val outerMin = (com.densityfold.engine.DisplayUtils.dpToDpi(1200, uiState.outerShortPx) ?: 72)
        .coerceAtLeast(72)
    val outerMax = (com.densityfold.engine.DisplayUtils.dpToDpi(200, uiState.outerShortPx) ?: 2000)
        .coerceAtMost(2000)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Density Fold", fontWeight = FontWeight.Bold)
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            "●",
                            fontSize = 11.sp,
                            color = if (uiState.isShizukuAvailable)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Text(
                            if (uiState.isShizukuAvailable) "Shizuku"
                            else "Нет связи",
                            fontSize = 12.sp,
                            color = if (uiState.isShizukuAvailable)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        if (!uiState.isShizukuAvailable) {
                            TextButton(onClick = viewModel::requestShizukuPermission) {
                                Text("Подключить")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            uiState.errorMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp
                    )
                }
            }

            uiState.successMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 13.sp
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Активный экран",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val display = uiState.activeDisplay
                    Text(
                        when {
                            display == null -> "Экран не определён"
                            display.isExternal -> "Внешний экран"
                            else -> "Внутренний экран"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (display != null) "${display.width} × ${display.height} px"
                            else "Разрешение недоступно",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        if (display != null && display.density > 0) {
                            val actualDpi = display.density
                            val actualDp = com.densityfold.engine.DisplayUtils.dpiToDp(
                                actualDpi, display.shortPx
                            )
                            Text(
                                "$actualDp dp · $actualDpi DPI",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Text(
                "Настройка DPI",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            DensitySettingCard(
                title = "Внутренний экран",
                dpi = uiState.innerDpi,
                dp = com.densityfold.engine.DisplayUtils.dpiToDp(
                    uiState.innerDpi, uiState.innerShortPx
                ),
                minDpi = innerMin,
                maxDpi = innerMax,
                onDpiChange = viewModel::setInnerDpi,
                onApply = viewModel::applyInnerDensity,
                enabled = uiState.isShizukuAvailable && !uiState.isLoading
            )

            DensitySettingCard(
                title = "Внешний экран",
                dpi = uiState.outerDpi,
                dp = com.densityfold.engine.DisplayUtils.dpiToDp(
                    uiState.outerDpi, uiState.outerShortPx
                ),
                minDpi = outerMin,
                maxDpi = outerMax,
                onDpiChange = viewModel::setOuterDpi,
                onApply = viewModel::applyOuterDensity,
                enabled = uiState.isShizukuAvailable && !uiState.isLoading
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { automationExpanded = !automationExpanded }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Автоматизация",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (uiState.isFoldMonitorEnabled)
                                    "Автоприменение включено"
                                else "Автоприменение выключено",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            if (automationExpanded) "⌃" else "⌄",
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (automationExpanded) {
                        Column(
                            modifier = Modifier.padding(
                                start = 14.dp, end = 14.dp, bottom = 14.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SettingSwitch(
                                title = "Автоприменение при складывании",
                                description = "Менять DPI при смене экрана",
                                checked = uiState.isFoldMonitorEnabled,
                                onCheckedChange = viewModel::setFoldMonitorEnabled
                            )
                            Divider()
                            SettingSwitch(
                                title = "Автозапуск при загрузке",
                                description = "Запускать монитор после перезагрузки",
                                checked = uiState.isAutoStartEnabled,
                                onCheckedChange = viewModel::setAutoStartEnabled
                            )
                            Divider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Density Fold",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    "v${BuildConfig.VERSION_NAME}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (!uiState.isOverlayPermissionGranted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Фоновое восстановление", fontWeight = FontWeight.Bold)
                        Text(
                            "Разрешение нужно для восстановления связи с Shizuku в фоне.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = onRequestOverlayPermission,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Разрешить фоновый запуск")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DensitySettingCard(
    title: String,
    dpi: Int,
    dp: Int,
    minDpi: Int,
    maxDpi: Int,
    onDpiChange: (Int) -> Unit,
    onApply: () -> Unit,
    enabled: Boolean
) {
    var value by remember(dpi) { mutableStateOf(dpi.toString()) }
    val safeMin = minDpi.coerceAtMost(maxDpi)
    val safeMax = maxDpi.coerceAtLeast(safeMin)
    val current = (value.toIntOrNull() ?: dpi).coerceIn(safeMin, safeMax)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("DPI:", fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it.filter(Char::isDigit).take(4)
                        value.toIntOrNull()?.takeIf { number ->
                            number in safeMin..safeMax
                        }?.let(onDpiChange)
                    },
                    modifier = Modifier.width(82.dp),
                    singleLine = true,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge
                )

                Text(
                    "$dp dp",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = onApply,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text("Применить", maxLines = 1)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        val next = (current - 5).coerceAtLeast(safeMin)
                        value = next.toString()
                        onDpiChange(next)
                    },
                    enabled = enabled
                ) {
                    Text("−", fontSize = 21.sp)
                }

                Slider(
                    value = current.toFloat(),
                    onValueChange = {
                        val next = it.toInt().coerceIn(safeMin, safeMax)
                        value = next.toString()
                        onDpiChange(next)
                    },
                    valueRange = safeMin.toFloat()..safeMax.toFloat(),
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )

                TextButton(
                    onClick = {
                        val next = (current + 5).coerceAtMost(safeMax)
                        value = next.toString()
                        onDpiChange(next)
                    },
                    enabled = enabled
                ) {
                    Text("+", fontSize = 21.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
