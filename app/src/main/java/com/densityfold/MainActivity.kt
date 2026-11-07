package com.densityfold

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> }
    
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
    
    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(topBar = { TopAppBar(title = { Text("Density Fold") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Активный дисплей: ${uiState.activeDisplay?.name ?: "Не определён"}")
                    Text("Текущий DPI: ${uiState.currentDensity}")
                    Text("Целевой DPI: ${uiState.targetDensity}")
                    Text("Shizuku: ${if (uiState.isShizukuAvailable) "OK" else "Нет"}", color = if (uiState.isShizukuAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
            
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Внутренний экран: ${uiState.innerDpi} dpi")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.setInnerDpi((uiState.innerDpi - 5).coerceAtLeast(100)) }) { Text("-5") }
                        Button(onClick = { viewModel.setInnerDpi((uiState.innerDpi + 5).coerceAtMost(1000)) }) { Text("+5") }
                    }
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Внешний экран: ${uiState.outerDpi} dpi")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.setOuterDpi((uiState.outerDpi - 5).coerceAtLeast(100)) }) { Text("-5") }
                        Button(onClick = { viewModel.setOuterDpi((uiState.outerDpi + 5).coerceAtMost(1000)) }) { Text("+5") }
                    }
                }
            }
            
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
}
