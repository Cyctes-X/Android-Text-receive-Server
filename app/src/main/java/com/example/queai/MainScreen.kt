package com.example.queai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.queai.ui.theme.QueAITheme
import androidx.compose.ui.graphics.Color as ComposeColor

@Composable
fun MainScreen(viewModel: ServerViewModel = viewModel()) {
    var port by remember { mutableStateOf("8080") }
    var status by remember { mutableStateOf("状态：未运行") }
    var textSize by remember { mutableStateOf(20f) }
    var backgroundOpacity by remember { mutableStateOf(0f) } // 默认背景不透明度为0
    var showColorPicker by remember { mutableStateOf(false) } // 调色盘对话框状态
    var isLocked by remember { mutableStateOf(false) } // 新增：锁定状态
    val context = LocalContext.current
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 权限结果处理 */ }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startServer(context, port.toIntOrNull() ?: 8080) { status = it }
        } else {
            status = "状态：缺少 Wi-Fi 权限"
            Toast.makeText(context, "需要 Wi-Fi 权限以获取 IP 地址", Toast.LENGTH_SHORT).show()
        }
    }

    // 修改：加载初始锁定状态，默认true（锁定）
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("QueAIPrefs", Context.MODE_PRIVATE)
        isLocked = prefs.getBoolean("lock_position", true)
    }

    // 注册全局广播接收器（用于调试）
    LaunchedEffect(Unit) {
        val filter = IntentFilter(OverlayService.BROADCAST_ACTION)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    intent.getStringExtra("message")?.let {
                        Log.d("QueAI", "MainScreen received message: $it")
                    }
                    intent.getFloatExtra("textSize", -1f).takeIf { it >= 0 }?.let {
                        textSize = it
                        Log.d("QueAI", "MainScreen received textSize: $it")
                    }
                }
            },
            filter,
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    QueAITheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("请输入端口号（例如 8080）") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                        return@Button
                    }
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_WIFI_STATE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        wifiPermissionLauncher.launch(Manifest.permission.ACCESS_WIFI_STATE)
                    } else {
                        viewModel.startServer(context, port.toIntOrNull() ?: 8080) { status = it }
                    }
                },
                enabled = !viewModel.isServerRunning(), // 启动时禁用
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("启动服务")
            }
            Button(
                onClick = { viewModel.stopServer { status = it } },
                enabled = viewModel.isServerRunning(), // 停止时启用
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("停止服务")
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            Slider(
                value = textSize,
                onValueChange = {
                    textSize = it + 10f
                    OverlayService.updateTextSize(context, textSize)
                },
                valueRange = 0f..50f,
                enabled = !isLocked, // 修改：锁定时禁用字体大小调整
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "字体大小 (10-60sp)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            // 修改：背景不透明度滑块
            Slider(
                value = backgroundOpacity,
                onValueChange = {
                    backgroundOpacity = it
                    OverlayService.updateBackgroundOpacity(context, backgroundOpacity)
                    Log.d(
                        "QueAI",
                        "Background opacity updated in MainScreen: $backgroundOpacity"
                    ) // 调试日志
                },
                valueRange = 0f..1f,
                enabled = !isLocked, // 修改：锁定时禁用背景不透明度调整
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "悬浮窗背景不透明度 (${String.format("%.0f%%", backgroundOpacity * 100)})",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            // 新增：锁定/解锁开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("锁定悬浮窗位置")
                Switch(
                    checked = isLocked,
                    onCheckedChange = { locked ->
                        isLocked = locked
                        // 保存到 SharedPreferences
                        val prefs = context.getSharedPreferences("QueAIPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("lock_position", locked).apply()
                        // 发送广播更新服务
                        OverlayService.updateLockPosition(context, locked)
                        Log.d("QueAI", "Lock position updated: $locked")
                    }
                )
            }
            // 调色盘模式颜色选择
            Button(
                onClick = { showColorPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("选择字体颜色")
            }

            // 调色盘对话框
            if (showColorPicker) {
                ColorPickerDialog(
                    onColorSelected = { selectedColor ->
                        val colorArgb = selectedColor.toArgb()
                        Log.d("QueAI", "Color selected in MainScreen: $colorArgb") // 添加调试日志
                        OverlayService.updateTextColor(context, colorArgb)
                        showColorPicker = false
                        Toast.makeText(context, "颜色已更新", Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { showColorPicker = false }
                )
            }
        }
    }
}

@Composable
fun ColorPickerDialog(
    onColorSelected: (ComposeColor) -> Unit,
    onDismiss: () -> Unit
) {
    var hue by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(1f) }
    var value by remember { mutableStateOf(1f) }
    var currentColor by remember { mutableStateOf(ComposeColor.Blue) } // 默认蓝色

    // 更新颜色
    LaunchedEffect(hue, saturation, value) {
        currentColor = ComposeColor.hsv(hue, saturation, value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择字体颜色") },
        text = {
            Column {
                // HSV 滑块
                Text("色调 (Hue)")
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f
                )
                Text("饱和度 (Saturation)")
                Slider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f
                )
                Text("明度 (Value)")
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..1f
                )
                // 预览
                Text(
                    text = "预览: Hello, World!",
                    color = currentColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onColorSelected(currentColor) }) {
                Text("应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}