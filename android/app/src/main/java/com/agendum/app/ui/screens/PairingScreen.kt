package com.agendum.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agendum.app.ui.PairingUiState
import com.agendum.app.ui.components.AgendumIcons
import com.agendum.app.ui.components.MistCard
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion
import com.agendum.app.ui.theme.tabularNums

@Composable
fun PairingScreen(
    pairing: PairingUiState,
    onScanClick: () -> Unit,
    onToggleManual: () -> Unit,
    onAddressChange: (String) -> Unit,
    onStartManualPairing: () -> Unit,
    onReturnOffline: () -> Unit,
    onOpenLegacySnapshot: () -> Unit,
    onDismissLegacySnapshot: () -> Unit,
    onExportAndSwitch: () -> Unit,
    onDiscardAndSwitch: () -> Unit,
    onDismissSwitchPrompt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(extColors.canvasBrush)
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Text(
                text = "Agendum",
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
                color = colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "连接电脑，同步日程",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(22.dp))

            MistCard(
                cornerRadius = 20.dp,
                elevation = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "在电脑打开「设置 → 手机日程同步」，生成二维码后扫描连接。",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = onScanClick,
                        enabled = !pairing.scanInProgress,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                    ) {
                        Icon(imageVector = AgendumIcons.QrCode, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "扫码连接电脑",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "二维码由你的电脑生成，配对凭据只可使用一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedButton(
                onClick = onToggleManual,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = extColors.cardSurface,
                    contentColor = colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(
                    text = "手动填写地址",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
            }

            AnimatedVisibility(
                visible = pairing.manualExpanded,
                enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
            ) {
                MistCard(
                    cornerRadius = 18.dp,
                    elevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "填写电脑设置中的 HTTPS 同步地址。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = pairing.address,
                            onValueChange = onAddressChange,
                            placeholder = { Text("例如 https://sync.example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = { onStartManualPairing() },
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = extColors.subtleSurface,
                                unfocusedContainerColor = extColors.subtleSurface,
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = colorScheme.primary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onStartManualPairing,
                            enabled = !pairing.isConnecting,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                        ) {
                            Text("连接电脑并显示配对码")
                        }

                        if (pairing.statusMessage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = pairing.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = extColors.warningInk,
                            )
                        }
                        if (pairing.pairCode.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = pairing.pairCode,
                                    style = MaterialTheme.typography.headlineMedium.tabularNums().copy(
                                        fontSize = 36.sp,
                                        letterSpacing = 6.sp,
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onPrimaryContainer,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "扫码仅用于连接你的电脑。遇到陌生页面，请取消并重新核对电脑上的二维码。",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )

            if (pairing.canReturnOffline) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onReturnOffline,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("返回离线日程")
                }
            }

            if (pairing.hasLegacySnapshot) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenLegacySnapshot,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("查看升级前的日程（只读）")
                }
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    pairing.legacySnapshotText?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissLegacySnapshot,
            title = { Text("升级前的日程") },
            text = {
                Box(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(text = text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissLegacySnapshot) {
                    Text("关闭")
                }
            },
        )
    }

    pairing.switchComputerPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onDismissSwitchPrompt,
            title = { Text("切换到另一台电脑？") },
            text = {
                Text("当前还有 ${prompt.pendingCount} 项未同步操作。先导出，或明确丢弃后再切换。")
            },
            confirmButton = {
                TextButton(onClick = onExportAndSwitch) {
                    Text("导出后切换")
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = onDiscardAndSwitch) {
                        Text("丢弃后切换", color = colorScheme.error)
                    }
                    TextButton(onClick = onDismissSwitchPrompt) {
                        Text("取消")
                    }
                }
            },
        )
    }
}
