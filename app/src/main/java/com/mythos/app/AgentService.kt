package com.mythos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Penjaga proses. Layanan ini TIDAK menjalankan agen: ia hanya membuat Android menganggap aplikasi sedang bekerja
 * (notifikasi foreground + wake lock parsial) selama satu tugas agen berlangsung, supaya tugas panjang (puluhan langkah,
 * build GitHub sampai 30 menit) tidak mati atau kehilangan jaringan begitu layar padam atau aplikasi dipindah ke latar belakang.
 * Dimulai dan dihentikan oleh AgentViewModel.
 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            // Tanpa foreground agen tetap jalan selama aplikasi berada di depan; jangan sampai tugas pengguna ikut gagal.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mythos:agent")
                .apply { setReferenceCounted(false) }
        }
        renewWakeLock()
        return START_NOT_STICKY
    }

    /** Kunci pendek yang diperpanjang selama layanan hidup: bila proses macet/layanan mati, kunci habis sendiri (tidak bocor 1 jam). */
    private fun renewWakeLock() {
        wakeLock?.acquire(WAKE_LOCK_MS)
        handler.removeCallbacks(renewer)
        handler.postDelayed(renewer, WAKE_LOCK_RENEW_MS)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val renewer = Runnable { renewWakeLock() }

    // Android 14: batas waktu foreground service dataSync; Android 15 memakai versi dengan tipe. Wajib berhenti segera atau sistem menganggap ANR.
    override fun onTimeout(startId: Int) {
        resetRuns()
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        resetRuns()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(renewer)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tugas agen", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mythos)
            .setContentTitle("Mythos sedang bekerja")
            .setContentText("Agen mengerjakan tugas Anda. Ketuk untuk kembali.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mythos_agent"
        private const val NOTIF_ID = 4242
        private const val WAKE_LOCK_MS = 15 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 10 * 60 * 1000L

        /** Jumlah tugas yang sedang berjalan; disinkronkan agar aman lintas thread. */
        private var runs = 0
        private val mainHandler = Handler(Looper.getMainLooper())
        private var pendingStop: Runnable? = null

        @Synchronized
        private fun incRuns(): Int = ++runs

        @Synchronized
        private fun decRuns(): Int {
            runs = maxOf(0, runs - 1)
            return runs
        }

        @Synchronized
        private fun resetRuns() {
            runs = 0
        }

        @Synchronized
        private fun currentRuns(): Int = runs

        fun start(ctx: Context) {
            val appCtx = ctx.applicationContext
            synchronized(this) {
                pendingStop?.let { mainHandler.removeCallbacks(it) }
                pendingStop = null
                incRuns()
            }
            try {
                ContextCompat.startForegroundService(appCtx, Intent(appCtx, AgentService::class.java))
            } catch (e: Exception) {
                decRuns()
            }
        }

        /** Berhenti sedikit tertunda: tugas yang selesai dalam hitungan milidetik tidak boleh mendahului startForeground(). */
        fun stop(ctx: Context) {
            val appCtx = ctx.applicationContext
            synchronized(this) {
                decRuns()
                pendingStop?.let { mainHandler.removeCallbacks(it) }
                val r = Runnable {
                    if (currentRuns() == 0) {
                        try {
                            appCtx.stopService(Intent(appCtx, AgentService::class.java))
                        } catch (e: Exception) {
                            // sudah berhenti
                        }
                    }
                }
                pendingStop = r
                mainHandler.postDelayed(r, 1_500L)
            }
        }
    }
}
