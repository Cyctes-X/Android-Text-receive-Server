package com.example.queai

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

class ServerViewModel : ViewModel() {
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var isRunning = mutableStateOf(false)
    private var context: Context? = null
    private val _messageFlow = MutableSharedFlow<String>(replay = 0) // 不缓存历史消息
    val messageFlow = _messageFlow.asSharedFlow()

    fun isServerRunning() = isRunning.value

    fun startServer(context: Context, port: Int, onStatusUpdate: (String) -> Unit) {
        this.context = context
        if (port !in 1024..65535) {
            onStatusUpdate("状态：无效端口")
            return
        }
        isRunning.value = true
        val ip = getLocalIpAddress(context)
        onStatusUpdate("状态：运行中，IP: $ip 端口: $port")
        context.startService(Intent(context, OverlayService::class.java))
        serverJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                Log.d("QueAI", "Server started on port $port")
                while (true) {
                    val client = serverSocket!!.accept()
                    client.soTimeout = 10000 // 10s 超时
                    Log.d("QueAI", "Client connected: ${client.inetAddress.hostAddress}")
                    var input: java.io.BufferedReader? = null
                    val fullMessage = StringBuilder() // 累加多行消息
                    try {
                        input = client.getInputStream().bufferedReader(StandardCharsets.UTF_16BE)
                        var line: String?
                        while (input.readLine().also { line = it } != null) { // 内层循环读多行，直到 EOF
                            val cleanLine = line!!.trim().replace("\uFFFD", "") // 去除�和空白
                            if (cleanLine.isNotEmpty()) {
                                fullMessage.append(cleanLine).append("\n") // 累加行 + 换行
                                Log.d("QueAI", "Accumulated line: $cleanLine")
                            }
                        }
                        val completeMessage =
                            fullMessage.toString().trimEnd { it == '\n' } // 去除末尾换行
                        if (completeMessage.isNotEmpty()) {
                            Log.d("QueAI", "Received complete message: $completeMessage")
                            _messageFlow.emit(completeMessage) // 发出完整消息
                            OverlayService.updateMessage(context, completeMessage)
                        }
                    } catch (e: java.io.EOFException) {
                        Log.d("QueAI", "Client disconnected normally (EOF)")
                    } catch (e: java.io.IOException) {
                        if (e.message?.contains("Connection reset") == true || e.message?.contains("Broken pipe") == true) {
                            Log.d("QueAI", "Client disconnected: ${e.message}")
                        } else {
                            Log.e("QueAI", "Message read error: ${e.message}", e)
                        }
                    } catch (e: Exception) {
                        Log.e("QueAI", "Unexpected error: ${e.message}", e)
                    } finally {
                        input?.close()
                        // 不关闭 client，等待客户端主动关闭
                    }
                }
            } catch (e: Exception) {
                Log.e("QueAI", "Server error: ${e.message}", e)
                onStatusUpdate("状态：错误 - ${e.message}")
                isRunning.value = false
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }
    }

    fun stopServer(onStatusUpdate: (String) -> Unit) {
        Log.d("QueAI", "Stopping server")
        serverJob?.cancel()
        serverSocket?.close()
        isRunning.value = false
        onStatusUpdate("状态：未运行")
        context?.stopService(Intent(context, OverlayService::class.java))
    }

    private fun getLocalIpAddress(context: Context): String {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        Log.d("QueAI", "Local IP: $ip")
        return ip
    }
}