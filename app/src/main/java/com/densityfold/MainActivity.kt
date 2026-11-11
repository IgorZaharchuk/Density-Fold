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

    var innerDpText by remember { mutableStateOf(uiState.innerDp.toString()) }
    var outerDpText by remember { mutableStateOf(uiState.outerDp.toString()) }
    var automationExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.innerDp) {
        innerDpText = uiState.innerDp.toString()
    }
    LaunchedEffect(uiState.outerDp) {
        outerDpText = uiState.outerDp.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Density Fold", fontWeight = FontWeight.Bold)

                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Текущее состояние",
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
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        if (display != null) {
                            "${display.width} × ${display.height} px"
                        } else {
                            "Разрешение недоступно"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Divider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Текущий DPI")
                        Text(
                            if (uiState.currentDensity > 0) {
                                uiState.currentDensity.toString()
                            } else {
                                "—"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Логическая ширина")
                        Text(
                            if (uiState.currentDp > 0) {
                                "${uiState.currentDp} dp"
                            } else {
                                "—"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Divider()

                    Text(
                        if (uiState.isShizukuAvailable) {
                            "Shizuku подключён"
                        } else {
                            "Ожидание подключения Shizuku"
                        },
                        color = if (uiState.isShizukuAvailable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )

                    if (!uiState.isShizukuAvailable) {
                        OutlinedButton(
                            onClick = { viewModel.requestShizukuPermission() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Запросить разрешение Shizuku")
                        }
                    }
                }
            }

            Text(
                "Настройки плотности",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            DensitySettingCard(
                title = "Внутренний экран",
                value = innerDpText,
                onValueChange = { value ->
                    innerDpText = value
                    value.toIntOrNull()?.takeIf { it in 200..1200 }?.let {
                        viewModel.setInnerDp(it)
                    }
                },
                onApply = { viewModel.applyInnerDensity() },
                enabled = uiState.isShizukuAvailable && !uiState.isLoading
            )

            DensitySettingCard(
                title = "Внешний экран",
                value = outerDpText,
                onValueChange = { value ->
                    outerDpText = value
                    value.toIntOrNull()?.takeIf { it in 200..1200 }?.let {
                        viewModel.setOuterDp(it)
                    }
                },
                onApply = { viewModel.applyOuterDensity() },
                enabled = uiState.isShizukuAvailable && !uiState.isLoading
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { automationExpanded = !automationExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Автоматизация",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (uiState.isFoldMonitorEnabled) {
                                    "Автоприменение включено"
                                } else {
                                    "Автоприменение выключено"
                                },
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
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingSwitch(
                                title = "Автоприменение при складывании",
                                description = "Менять плотность при смене экрана",
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
                        }
                    }
                }
            }

            if (!uiState.isOverlayPermissionGranted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Фоновое восстановление",
                            fontWeight = FontWeight.Bold
                        )
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

            uiState.errorMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            uiState.successMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun DensitySettingCard(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    enabled: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("Ширина, dp") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                Button(
                    onClick = onApply,
                    enabled = enabled
                ) {
                    Text("Применить")
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
