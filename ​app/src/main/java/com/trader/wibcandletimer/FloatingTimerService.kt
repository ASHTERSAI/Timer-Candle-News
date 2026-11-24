package com.trader.wibcandletimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class FloatingTimerService : Service() {

    companion object {
        const val EXTRA_TIMEFRAME_MINUTES = "extra_tf_min"
        const val CHANNEL_ID = "wib_candle_timer_channel"
        const val NOTIF_ID = 101

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var tvWibClock: TextView
    private lateinit var tvTimeframe: TextView
    private lateinit var tvCountdown: TextView

    private val timeframes = listOf(1, 5, 15, 30, 60)
    private var currentTfIndex = 0 // Default M1

    private val handler = Handler(Looper.getMainLooper())
    private val wibFormat = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundCompat()

        if (!NtpTimeSync.isSynced) {
            Thread { NtpTimeSync.syncBlocking() }.start()
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_forex_timer, null)

        tvWibClock = overlayView.findViewById(R.id.tvWibClock)
        tvTimeframe = overlayView.findViewById(R.id.tvTimeframe)
        tvCountdown = overlayView.findViewById(R.id.tvCountdown)
        val btnClose = overlayView.findViewById<TextView>(R.id.btnCloseOverlay)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 180
        }

        // Ketuk badge timeframe untuk ganti cepat M1 -> M5 -> M15 -> M30 -> H1
        tvTimeframe.setOnClickListener {
            currentTfIndex = (currentTfIndex + 1) % timeframes.size
            updateDisplay()
        }

        btnClose.setOnClickListener {
            stopSelf()
        }

        // Fitur geser (drag) widget melayang
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tfMinutes = intent?.getIntExtra(EXTRA_TIMEFRAME_MINUTES, -1) ?: -1
        val idx = timeframes.indexOf(tfMinutes)
        if (idx >= 0) {
            currentTfIndex = idx
            updateDisplay()
        }
        return START_STICKY
    }

    private fun updateDisplay() {
        val nowUtc = NtpTimeSync.nowUtcMs()
        val tfMinutes = timeframes[currentTfIndex]
        val periodMs = tfMinutes * 60_000L

        val elapsedInCandle = nowUtc % periodMs
        val remainingMs = periodMs - elapsedInCandle
        val totalSeconds = (remainingMs + 999L) / 1000L
        val mins = (totalSeconds / 60L) % 60L
        val secs = totalSeconds % 60L

        tvWibClock.text = "${wibFormat.format(Date(nowUtc))} WIB"
        tvTimeframe.text = if (tfMinutes == 60) "H1" else "M$tfMinutes"
        tvCountdown.text = if (totalSeconds == 3600L) {
            "60:00"
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }

        if (totalSeconds <= 10L) {
            tvCountdown.setTextColor(Color.parseColor("#FF5252"))
        } else {
            tvCountdown.setTextColor(Color.parseColor("#00E676"))
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Widget Hitung Mundur Candle",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WIB Forex Candle Aktif")
            .setContentText("Widget melayang berjalan di atas layar.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(ticker)
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
