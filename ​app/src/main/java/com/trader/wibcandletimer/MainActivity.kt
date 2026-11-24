package com.trader.wibcandletimer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var tvMainClock: TextView
    private lateinit var tvStatusNtp: TextView
    private lateinit var rgTimeframe: RadioGroup
    private lateinit var btnToggleOverlay: Button
    private lateinit var btnSyncNtp: Button

    private val handler = Handler(Looper.getMainLooper())
    private val wibFormat = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }

    private val clockUpdater = object : Runnable {
        override fun run() {
            val nowUtc = NtpTimeSync.nowUtcMs()
            tvMainClock.text = "${wibFormat.format(Date(nowUtc))} WIB"
            refreshButtonLabel()
            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMainClock = findViewById(R.id.tvMainClock)
        tvStatusNtp = findViewById(R.id.tvStatusNtp)
        rgTimeframe = findViewById(R.id.rgTimeframe)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
        btnSyncNtp = findViewById(R.id.btnSyncNtp)

        syncNtpNow()

        btnSyncNtp.setOnClickListener {
            syncNtpNow()
        }

        rgTimeframe.setOnCheckedChangeListener { _, _ ->
            if (FloatingTimerService.isRunning) {
                startOverlayService()
            }
        }

        btnToggleOverlay.setOnClickListener {
            if (FloatingTimerService.isRunning) {
                stopService(Intent(this, FloatingTimerService::class.java))
                refreshButtonLabel()
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(
                        this,
                        "Izinkan 'Tampil di atas aplikasi lain' lalu tekan lagi.",
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    startOverlayService()
                }
            }
        }
    }

    private fun selectedTimeframeMinutes(): Int {
        return when (rgTimeframe.checkedRadioButtonId) {
            R.id.rbM1 -> 1
            R.id.rbM5 -> 5
            R.id.rbM15 -> 15
            R.id.rbM30 -> 30
            R.id.rbH1 -> 60
            else -> 1
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, FloatingTimerService::class.java).apply {
            putExtra(FloatingTimerService.EXTRA_TIMEFRAME_MINUTES, selectedTimeframeMinutes())
        }
        ContextCompat.startForegroundService(this, intent)
        refreshButtonLabel()
    }

    private fun refreshButtonLabel() {
        btnToggleOverlay.text = if (FloatingTimerService.isRunning) {
            "MATIKAN WIDGET MELAYANG"
        } else {
            "AKTIFKAN WIDGET MELAYANG"
        }
    }

    private fun syncNtpNow() {
        tvStatusNtp.text = "Menyinkronkan ke server waktu internasional..."
        Thread {
            val ok = NtpTimeSync.syncBlocking()
            runOnUiThread {
                tvStatusNtp.text = if (ok) {
                    "Tersinkronisasi: ${NtpTimeSync.lastServer} (UTC+7)"
                } else {
                    "Gagal konek NTP, memakai jam sistem HP"
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockUpdater)
    }
}
