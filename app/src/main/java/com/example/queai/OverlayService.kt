package com.example.queai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View.OnTouchListener
import android.view.WindowManager
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import kotlin.math.abs

class OverlayService : LifecycleService() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView
    private var currentTextSize = 20f
    private var broadcastReceiver: BroadcastReceiver? = null
    private lateinit var sharedPreferences: SharedPreferences // SharedPreferences
    private var isDraggable = true // 新增：拖动标志，默认解锁
    private var initialX = 0f // 新增：拖动起始 X
    private var initialY = 0f // 新增：拖动起始 Y
    private var initialTouchX = 0f // 新增：初始触摸 X
    private var initialTouchY = 0f // 新增：初始触摸 Y

    @Suppress("OVERRIDES_EMPTY_IMPL")
    override fun onBind(intent: Intent): IBinder? {
        val binder = super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("QueAI", "OverlayService onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 初始化 SharedPreferences
        sharedPreferences = getSharedPreferences("QueAIPrefs", MODE_PRIVATE)

        // 修改：加载锁定状态，默认true（锁定）
        isDraggable = !sharedPreferences.getBoolean("lock_position", true)
        var savedX = sharedPreferences.getInt("window_x", 0)
        var savedY = sharedPreferences.getInt("window_y", 0)

        // 新增：首次启动时设置默认顶层居中位置
        if (savedX == 0 && savedY == 0) {
            val displayMetrics: DisplayMetrics = Resources.getSystem().displayMetrics
            val screenWidth = displayMetrics.widthPixels
            savedX = (screenWidth / 2) - 100 // 假设视图宽度约200px，居中调整
            savedY = 0 // 顶部
            // 保存默认位置
            sharedPreferences.edit()
                .putInt("window_x", savedX)
                .putInt("window_y", savedY)
                .apply()
            Log.d("QueAI", "Set default centered position: x=$savedX, y=$savedY")
        }

        overlayView = TextView(this).apply {
            text = "等待消息..."
            // 从 SharedPreferences 加载颜色，默认蓝色
            val savedColor = sharedPreferences.getInt("text_color", Color.BLUE)
            setTextColor(savedColor)
            Log.d("QueAI", "Loaded saved textColor: $savedColor")
            textSize = currentTextSize
            // 修改：设置背景为白色半透明，使用加载的不透明度
            val savedBackgroundOpacity = sharedPreferences.getFloat("background_opacity", 0f)
            setBackgroundColor(Color.argb((savedBackgroundOpacity * 255).toInt(), 255, 255, 255))
            Log.d("QueAI", "Loaded saved background opacity: $savedBackgroundOpacity")
            setPadding(0, 0, 0, 0) // 去除填充
            isSingleLine = false // 支持多行
            setHorizontallyScrolling(false) // 允许换行
            alpha = 1.0f // 保持视图整体不透明，确保字体不透明
            // 新增：明确禁用焦点，以增强事件穿透
            isFocusable = false
            isFocusableInTouchMode = false
            // 修改：仅在解锁时设置拖动监听器
            if (isDraggable) {
                setOnTouchListener(createDragListener())
            }
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = if (isDraggable) {
            // 解锁：移除 NOT_TOUCHABLE，允许拖动
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            // 锁定：添加 NOT_TOUCHABLE，防止拖动
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 使用绝对定位，支持拖动
            x = savedX
            y = savedY
            gravity = Gravity.TOP or Gravity.LEFT // 改为 LEFT 以支持 x/y 绝对定位
        }

        try {
            windowManager.addView(overlayView, params)
            Log.d("QueAI", "OverlayView added with drag support, draggable: $isDraggable")
        } catch (e: Exception) {
            Log.e("QueAI", "Failed to add OverlayView: ${e.message}")
            stopSelf()
        }

        // 注册全局广播接收器
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra("message")?.let { rawMessage ->
                    Log.d(
                        "QueAI",
                        "OverlayService received message: ${rawMessage.take(100)}..."
                    ) // 截断日志
                    // 优化：限制消息长度，避免视图过度膨胀
                    val truncatedMessage = if (rawMessage.length > 1000) rawMessage.substring(
                        0,
                        1000
                    ) + "..." else rawMessage
                    // 优化：使用 post 延迟更新 UI，避免阻塞主线程
                    overlayView.post {
                        overlayView.text = truncatedMessage
                        Log.d("QueAI", "UI updated for message")
                        refreshViewLayout() // 新增：刷新布局以保持 flags
                    }
                }
                intent.getFloatExtra("textSize", -1f).takeIf { it >= 0 }?.let { size ->
                    Log.d("QueAI", "OverlayService received textSize: $size")
                    currentTextSize = size
                    overlayView.post {
                        overlayView.textSize = size
                        refreshViewLayout() // 新增：刷新布局以保持 flags
                    }
                }
                // 修改：处理 backgroundOpacity 更新（取代原 alpha）
                intent.getFloatExtra("backgroundOpacity", -1f).takeIf { it >= 0f && it <= 1f }
                    ?.let { opacity ->
                        Log.d("QueAI", "OverlayService received backgroundOpacity: $opacity")
                        // 更新背景为白色半透明，不影响字体
                        val bgColor = Color.argb((opacity * 255).toInt(), 255, 255, 255)
                        overlayView.post {
                            overlayView.setBackgroundColor(bgColor)
                            refreshViewLayout() // 新增：刷新布局以保持 flags
                        }
                        // 保存到 SharedPreferences 以实现持久化
                        sharedPreferences.edit().putFloat("background_opacity", opacity).apply()
                        Log.d("QueAI", "Background opacity saved to preferences: $opacity")
                    }
                // 处理 textColor 更新
                intent.getIntExtra("textColor", -1).takeIf { it != -1 }?.let { color ->
                    Log.d("QueAI", "OverlayService received textColor: $color")
                    overlayView.post {
                        overlayView.setTextColor(color)
                        refreshViewLayout() // 新增：刷新布局以保持 flags
                    }
                    // 保存到 SharedPreferences 以实现持久化
                    sharedPreferences.edit().putInt("text_color", color).apply()
                    Log.d("QueAI", "TextColor saved to preferences: $color")
                }
                // 修改：处理锁定位置更新
                intent.getBooleanExtra("lockPosition", false).let { locked ->
                    if (isDraggable != !locked) {
                        isDraggable = !locked
                        Log.d("QueAI", "Draggable updated: $isDraggable")
                        // 动态更新 flags 和监听器
                        val newFlags = if (isDraggable) {
                            overlayView.setOnTouchListener(createDragListener()) // 添加拖动监听器
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        } else {
                            overlayView.setOnTouchListener(null) // 移除拖动监听器
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        }
                        val updatedParams = overlayView.layoutParams as WindowManager.LayoutParams
                        updatedParams.flags = newFlags
                        windowManager.updateViewLayout(overlayView, updatedParams)
                    }
                }
            }
        }
        val filter = IntentFilter(BROADCAST_ACTION)
        registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    // 新增：刷新视图布局，确保 flags 和位置保持不变
    private fun refreshViewLayout() {
        val currentParams = overlayView.layoutParams as WindowManager.LayoutParams
        // 保留当前 flags（锁定/解锁状态）
        val flags = if (isDraggable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        currentParams.flags = flags
        windowManager.updateViewLayout(overlayView, currentParams)
        Log.d("QueAI", "View layout refreshed, draggable: $isDraggable")
    }

    // 新增：创建拖动监听器（提取为方法，便于动态添加/移除）
    private fun createDragListener(): OnTouchListener {
        return OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayView.x
                    initialY = overlayView.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    v.parent.requestDisallowInterceptTouchEvent(true) // 防止父视图拦截
                    false // 继续分发事件
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggable) {
                        Log.d("QueAI", "Drag ignored due to lock")
                        false
                    } else {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        // 最小移动阈值，避免抖动
                        if (abs(dx) > 5 || abs(dy) > 5) {
                            windowManager.updateViewLayout(v, updateParams(dx.toInt(), dy.toInt()))
                        }
                        false
                    }
                }

                else -> false
            }
        }
    }

    // 新增：更新 params 的 x/y
    private fun updateParams(dx: Int, dy: Int): WindowManager.LayoutParams {
        val params = overlayView.layoutParams as WindowManager.LayoutParams
        params.x = (initialX + dx).toInt()
        params.y = (initialY + dy).toInt()
        // 保存位置
        sharedPreferences.edit()
            .putInt("window_x", params.x)
            .putInt("window_y", params.y)
            .apply()
        Log.d("QueAI", "Position updated: x=${params.x}, y=${params.y}")
        return params
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("QueAI", "OverlayService onDestroy")
        try {
            windowManager.removeView(overlayView)
            Log.d("QueAI", "OverlayView removed")
        } catch (e: Exception) {
            Log.e("QueAI", "Failed to remove OverlayView: ${e.message}")
        }
        broadcastReceiver?.let {
            unregisterReceiver(it)
        }
    }

    companion object {
        const val BROADCAST_ACTION = "com.example.queai.UPDATE_OVERLAY"

        fun updateMessage(context: Context, message: String) {
            Log.d("QueAI", "Sending broadcast message: ${message.take(100)}...")
            val intent = Intent(BROADCAST_ACTION).apply {
                putExtra("message", message)
                setPackage("com.example.queai")
            }
            context.sendBroadcast(intent)
        }

        fun updateTextSize(context: Context, size: Float) {
            Log.d("QueAI", "Sending broadcast textSize: $size")
            val intent = Intent(BROADCAST_ACTION).apply {
                putExtra("textSize", size)
                setPackage("com.example.queai")
            }
            context.sendBroadcast(intent)
        }

        fun updateAlpha(context: Context, alpha: Float) {
            Log.d("QueAI", "Sending broadcast alpha: $alpha")
            val intent = Intent(BROADCAST_ACTION).apply {
                putExtra("alpha", alpha)
                setPackage("com.example.queai")
            }
            context.sendBroadcast(intent)
        }

        // 新增：更新背景不透明度
        fun updateBackgroundOpacity(context: Context, opacity: Float) {
            Log.d("QueAI", "Sending broadcast backgroundOpacity: $opacity")
            val intent = Intent(BROADCAST_ACTION).apply {
                putExtra("backgroundOpacity", opacity)
                setPackage("com.example.queai")
            }
            context.sendBroadcast(intent)
        }

        // 新增：更新锁定位置
        fun updateLockPosition(context: Context, locked: Boolean) {
            Log.d("QueAI", "Sending broadcast lockPosition: $locked")
            val intent = Intent(BROADCAST_ACTION).apply {
                putExtra("lockPosition", locked)
                setPackage("com.example.queai")
            }
            context.sendBroadcast(intent)
        }

        fun updateTextColor(context: Context, color: Int) {
            Log.d("QueAI", "Sending broadcast textColor: $color")
            val intent = Intent(BROADCAST_ACTION).apply {
                putExtra("textColor", color)
                setPackage("com.example.queai")
            }
            context.sendBroadcast(intent)
        }
    }
}