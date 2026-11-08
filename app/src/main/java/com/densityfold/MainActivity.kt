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
    
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Запрос разрешения на уведомления для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
    
    var innerDpiText by remember { mutableStateOf(uiState.innerDpi.toString()) }
    var outerDpiText by remember { mutableStateOf(uiState.outerDpi.toString()) }

    LaunchedEffect(uiState.innerDpi) { innerDpiText = uiState.innerDpi.toString() }
    LaunchedEffect(uiState.outerDpi) { outerDpiText = uiState.outerDpi.toString() }

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
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Настройка плотности", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = innerDpiText,
                            onValueChange = { newText ->
                                innerDpiText = newText
                                newText.toIntOrNull()?.let { dpi ->
                                    if (dpi in 100..1000) viewModel.setInnerDpi(dpi)
                                }
                            },
                            label = { Text("Внутренний, dpi") },
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
                            value = outerDpiText,
                            onValueChange = { newText ->
                                outerDpiText = newText
                                newText.toIntOrNull()?.let { dpi ->
                                    if (dpi in 100..1000) viewModel.setOuterDpi(dpi)
                                }
                            },
                            label = { Text("Внешний, dpi") },
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

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Диагностика") },
            text = { Text(uiState.diagnosticInfo) },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("OK") } }
        )
    }
}
