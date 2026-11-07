package com.densityfold

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.densityfold.engine.ShizukuManager
import com.densityfold.ui.theme.DensityFoldTheme
import com.densityfold.viewmodel.MainViewModel
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        // После изменения прав в Shizuku, пользователь нажмёт "Обновить"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        setContent {
            DensityFoldTheme {
                val viewModel: MainViewModel = viewModel()
                MainScreen(viewModel = viewModel)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Автоматически проверяем статус Shizuku при возврате в приложение
        viewModel.refreshDisplays()
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

    Scaffold(topBar = { 
        TopAppBar(
            title = { Text("Density Fold") },
            actions = {
                IconButton(onClick = { showDiagnostics = true }) {
                    Text("ℹ️")
                }
            }
        ) 
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Карточка статуса
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Дисплей: ${uiState.activeDisplay?.name ?: "Не определён"}")
                    Text("Display ID: ${uiState.activeDisplay?.displayId ?: "?"}")
                    Text("Разрешение: ${uiState.activeDisplay?.width ?: 0} x ${uiState.activeDisplay?.height ?: 0}")
                    Text("Текущий DPI: ${uiState.currentDensity}")
                    Text("Shizuku: ${if (uiState.isShizukuAvailable) "Подключено" else "Нет"}", 
                         color = if (uiState.isShizukuAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    
                    if (!uiState.isShizukuAvailable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.requestShizukuPermission() }) {
                            Text("Запросить разрешение Shizuku")
                        }
                        Button(onClick = { viewModel.refreshDisplays() }, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Обновить статус")
                        }
                    }
                }
            }

            // Настройки DPI
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Внутренний экран: ${uiState.innerDpi} dpi", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(-5, -1, 1, 5).forEach { step ->
                            Button(onClick = { viewModel.setInnerDpi((uiState.innerDpi + step).coerceIn(100, 1000)) }) { 
                                Text(if (step > 0) "+$step" else "$step") 
                            }
                        }
                    }
                    Divider()
                    Text("Внешний экран: ${uiState.outerDpi} dpi", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(-5, -1, 1, 5).forEach { step ->
                            Button(onClick = { viewModel.setOuterDpi((uiState.outerDpi + step).coerceIn(100, 1000)) }) { 
                                Text(if (step > 0) "+$step" else "$step") 
                            }
                        }
                    }
                }
            }

            // Кнопка применения
            Button(
                onClick = { viewModel.applyCurrentScreenDensity() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = uiState.isShizukuAvailable && !uiState.isLoading
            ) {
                Text(if (uiState.isLoading) "Применение..." else "Применить к текущему экрану")
            }

            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            uiState.successMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
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
