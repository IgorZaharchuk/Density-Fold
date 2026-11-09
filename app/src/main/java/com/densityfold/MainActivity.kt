package com.densityfold

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        setContent { DensityFoldTheme { MainScreen(viewModel = mainViewModel) } }
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
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }
    var showDirectGrantConfirm by remember { mutableStateOf(false) }
    
    var innerDpText by remember { mutableStateOf(uiState.innerDp.toString()) }
    var outerDpText by remember { mutableStateOf(uiState.outerDp.toString()) }

    LaunchedEffect(uiState.innerDp) { innerDpText = uiState.innerDp.toString() }
    LaunchedEffect(uiState.outerDp) { outerDpText = uiState.outerDp.toString() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Density Fold", fontWeight = FontWeight.Bold)
                        Text("Galaxy Z Fold 7", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { showDiagnostics = true }) {
                        Icon(Icons.Default.Info, "Диагностика")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val displayName = if (uiState.activeDisplay?.isExternal == true) "Внешний экран" else "Внутренний экран"
                    Text(displayName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Разрешение: ${uiState.activeDisplay?.width ?: 0} × ${uiState.activeDisplay?.height ?: 0} px")
                    Text("Текущий DPI: ${uiState.currentDensity}")
                    Text("Логическая ширина: ${uiState.currentDp} dp")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Доступ: ${if (uiState.isShizukuAvailable) "Shizuku подключен" else "Shizuku не подключен"}",
                        color = if (uiState.isShizukuAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    if (!uiState.isShizukuAvailable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.requestShizukuPermission() }) {
                            Text("Запросить разрешение Shizuku")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Автоприменение при складывании", fontWeight = FontWeight.Bold)
                            Text("Мониторить изменение экрана", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = uiState.isFoldMonitorEnabled,
                            onCheckedChange = { viewModel.setFoldMonitorEnabled(it) }
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Автозапуск при загрузке", fontWeight = FontWeight.Bold)
                            Text("Запускать монитор после перезагрузки", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = uiState.isAutoStartEnabled,
                            onCheckedChange = { viewModel.setAutoStartEnabled(it) }
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Эксперимент: прямое управление DPI", fontWeight = FontWeight.Bold)
                    Text(
                        if (uiState.directPermissionGranted)
                            "WRITE_SECURE_SETTINGS: разрешение выдано"
                        else
                            "WRITE_SECURE_SETTINGS: разрешение не выдано",
                        color = if (uiState.directPermissionGranted)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    if (!uiState.directPermissionGranted) {
                        Button(
                            onClick = { showDirectGrantConfirm = true },
                            enabled = uiState.isShizukuAvailable && !uiState.isLoading
                        ) { Text("Выдать разрешение через Shizuku") }
                    }
                    Button(
                        onClick = { viewModel.testDirectDensity() },
                        enabled = uiState.directPermissionGranted && !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Проверить прямое изменение DPI") }
                    if (uiState.directTestResult.isNotBlank()) {
                        Text(uiState.directTestResult, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Тест временно меняет DPI на 1 пункт и затем восстанавливает исходное состояние. Основные кнопки и автоприменение остаются на Shizuku.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Настройка плотности (dp)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Допустимый диапазон: 200–1200 dp", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = innerDpText,
                            onValueChange = { newText ->
                                innerDpText = newText
                                newText.toIntOrNull()?.let { dp ->
                                    if (dp in 200..1200) viewModel.setInnerDp(dp)
                                }
                            },
                            label = { Text("Внутренний, dp") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.applyInnerDensity() },
                            enabled = uiState.isShizukuAvailable && !uiState.isLoading
                        ) { Text("Применить") }
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = outerDpText,
                            onValueChange = { newText ->
                                outerDpText = newText
                                newText.toIntOrNull()?.let { dp ->
                                    if (dp in 200..1200) viewModel.setOuterDp(dp)
                                }
                            },
                            label = { Text("Внешний, dp") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.applyOuterDensity() },
                            enabled = uiState.isShizukuAvailable && !uiState.isLoading
                        ) { Text("Применить") }
                    }
                }
            }

            uiState.errorMessage?.let { 
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            uiState.successMessage?.let { 
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }

    if (showDirectGrantConfirm) {
        AlertDialog(
            onDismissRequest = { showDirectGrantConfirm = false },
            title = { Text("Выдать системное разрешение?") },
            text = {
                Text(
                    "Density Fold выполнит через Shizuku команду pm grant для WRITE_SECURE_SETTINGS. " +
                    "Это позволит приложению отдельно проверять прямой вызов системного управления DPI. " +
                    "Основные функции пока останутся на Shizuku."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDirectGrantConfirm = false
                    viewModel.grantDirectPermission()
                }) { Text("Выдать") }
            },
            dismissButton = {
                TextButton(onClick = { showDirectGrantConfirm = false }) { Text("Отмена") }
            }
        )
    }

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Диагностика") },
            text = { Text(uiState.diagnosticInfo) },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("OK") } }
        )
    }
}
