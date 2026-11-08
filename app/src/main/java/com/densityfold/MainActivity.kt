package com.densityfold

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.densityfold.engine.ShizukuManager
import com.densityfold.ui.theme.DensityFoldTheme
import com.densityfold.viewmodel.MainViewModel
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        setContent {
            DensityFoldTheme { MainScreen(viewModel = mainViewModel) }
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
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }
    
    // Отдельные состояния для текстовых полей (разрешают промежуточный ввод)
    var innerDpiText by remember { mutableStateOf(uiState.innerDpi.toString()) }
    var outerDpiText by remember { mutableStateOf(uiState.outerDpi.toString()) }

    // Синхронизируем текст при изменении значений из ViewModel (например, при загрузке)
    LaunchedEffect(uiState.innerDpi) { innerDpiText = uiState.innerDpi.toString() }
    LaunchedEffect(uiState.outerDpi) { outerDpiText = uiState.outerDpi.toString() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Плотность Fold", fontWeight = FontWeight.Bold)
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
                    Text("Дисплей ${uiState.activeDisplay?.displayId ?: "?"} · ${uiState.activeDisplay?.width ?: 0}×${uiState.activeDisplay?.height ?: 0} px")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Доступ: ${if (uiState.isShizukuAvailable) "Shizuku, команда wm выполнится в shell" else "Shizuku не подключен"}",
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
                    Text("Плотность экранов", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    
                    OutlinedTextField(
                        value = innerDpiText,
                        onValueChange = { newText ->
                            innerDpiText = newText // Всегда обновляем текст поля
                            // Сохраняем в ViewModel только если введено корректное число
                            newText.toIntOrNull()?.let { dpi ->
                                if (dpi in 100..1000) {
                                    viewModel.setInnerDpi(dpi)
                                }
                            }
                        },
                        label = { Text("Внутренний экран, dpi") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = outerDpiText,
                        onValueChange = { newText ->
                            outerDpiText = newText // Всегда обновляем текст поля
                            newText.toIntOrNull()?.let { dpi ->
                                if (dpi in 100..1000) {
                                    viewModel.setOuterDpi(dpi)
                                }
                            }
                        },
                        label = { Text("Внешний экран, dpi") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            Button(
                onClick = { viewModel.applyCurrentScreenDensity() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = uiState.isShizukuAvailable && !uiState.isLoading
            ) {
                Text(if (uiState.isLoading) "Применение..." else "Применить к текущему экрану", fontSize = 16.sp)
            }

            uiState.errorMessage?.let { 
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            uiState.successMessage?.let { 
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Диагностика дисплеев") },
            text = { Text(uiState.diagnosticInfo) },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("OK") } }
        )
    }
}
