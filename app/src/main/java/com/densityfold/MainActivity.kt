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
            DensityFoldTheme {
                MainScreen(viewModel = mainViewModel)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (::mainViewModel.isInitialized) {
            // Только обновляем информацию, НЕ применяем DPI
            mainViewModel.refreshDisplays()
        }
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::mainViewModel.isInitialized) {
            // Только обновляем информацию при смене экрана, НЕ применяем DPI
            mainViewModel.refreshDisplays()
        }
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
            // Карточка активного экрана
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val displayName = if (uiState.activeDisplay?.isExternal == true) "Внешний экран" else "Внутренний экран"
                    
                    Text(
                        displayName,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
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

            // Настройки DPI
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Плотность экранов", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    
                    OutlinedTextField(
                        value = uiState.innerDpi.toString(),
                        onValueChange = { 
                            it.toIntOrNull()?.let { dpi -> viewModel.setInnerDpi(dpi.coerceIn(100, 1000)) }
                        },
                        label = { Text("Внутренний экран, dpi") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = uiState.outerDpi.toString(),
                        onValueChange = { 
                            it.toIntOrNull()?.let { dpi -> viewModel.setOuterDpi(dpi.coerceIn(100, 1000)) }
                        },
                        label = { Text("Внешний экран, dpi") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Кнопка применения
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
