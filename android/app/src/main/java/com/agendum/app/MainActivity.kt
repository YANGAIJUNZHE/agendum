package com.agendum.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.agendum.app.gateway.AndroidScheduleGateway
import com.agendum.app.ui.AgendumApp
import com.agendum.app.ui.ScheduleViewModel
import com.agendum.app.ui.UiEvent
import com.agendum.app.ui.theme.AgendumTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** 入口 Activity：负责系统边到边样式、扫码/文档选择器与生命周期转发；状态与同步交由 ViewModel。 */
class MainActivity : ComponentActivity() {
    private val viewModel: ScheduleViewModel by viewModels {
        ScheduleViewModel.Factory(AndroidScheduleGateway(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            intent?.dataString?.let { viewModel.handleIncomingLink(it) }
        }

        setContent {
            val darkTheme = isSystemInDarkTheme()
            DisposableEffect(darkTheme) {
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                onDispose {}
            }

            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                        Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
                viewModel.onScanResult(result?.contents)
            }

            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                val text = viewModel.consumePendingExportContent()
                if (uri == null || text == null) {
                    viewModel.onExportCompleted(success = false)
                    return@rememberLauncherForActivityResult
                }
                lifecycleScope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(text.toByteArray(Charsets.UTF_8))
                            } ?: throw IOException("无法写入文件")
                        }
                    }
                    outcome.onSuccess {
                        viewModel.onExportCompleted(success = true)
                    }.onFailure { error ->
                        viewModel.onExportCompleted(
                            success = false,
                            errorMessage = error.message ?: "操作未完成，请重试",
                        )
                    }
                }
            }

            LaunchedEffect(viewModel) {
                viewModel.events.collect { event ->
                    when (event) {
                        UiEvent.LaunchScanner -> {
                            try {
                                val options = ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("扫描电脑「手机日程同步」中的二维码")
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false)
                                scanLauncher.launch(options)
                            } catch (_: RuntimeException) {
                                viewModel.onScanLaunchFailed()
                            }
                        }
                        is UiEvent.LaunchExportDocument -> {
                            try {
                                exportLauncher.launch(event.suggestedName)
                            } catch (_: RuntimeException) {
                                viewModel.onExportLaunchFailed()
                            }
                        }
                    }
                }
            }

            AgendumTheme(darkTheme = darkTheme) {
                AgendumApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIncomingLink(intent.dataString)
    }
}
