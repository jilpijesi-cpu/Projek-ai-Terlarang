package com.mythos.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mythos.app.MainActivity
import com.mythos.app.R
import org.json.JSONObject

/**
 * Notifikasi tenggat terjadwal. Daftar disimpan di SharedPreferences supaya bisa dipasang ulang setelah ponsel dihidupkan ulang.
 * Memakai alarm tidak-presisi (setAndAllowWhileIdle): tidak butuh izin alarm tepat, dan bisa terlambat beberapa menit.
 */
object DeadlineNotices {
    const val ACTION_FIRE = "com.mythos.app.DEADLINE_FIRE"
    private const val PREFS = "deadline_notices"
    private const val CHANNEL_ID = "mythos_deadline"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun schedule(ctx: Context, id: String, title: String, text: String, atMillis: Long) {
        val o = JSONObject().put("title", title).put("text", text).put("at", atMillis)
        prefs(ctx).edit().putString(id, o.toString()).apply()
        arm(ctx, id, atMillis)
    }

    private fun pending(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, DeadlineReceiver::class.java).setAction(ACTION_FIRE)
            .setData(Uri.parse("mythos://notice/" + Uri.encode(id))).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun arm(ctx: Context, id: String, atMillis: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(ctx, id))
    }

    /** Dipanggil setelah ponsel hidup ulang: pasang lagi semua jadwal; yang sudah lewat langsung ditampilkan. */
    fun rearm(ctx: Context) {
        val now = System.currentTimeMillis()
        for (id in prefs(ctx).all.keys.toList()) {
            val at = runCatching { JSONObject(prefs(ctx).getString(id, "") ?: "").getLong("at") }.getOrNull()
            if (at == null) { prefs(ctx).edit().remove(id).apply(); continue }
            if (at <= now) fire(ctx, id) else arm(ctx, id, at)
        }
    }

    fun fire(ctx: Context, id: String) {
        val raw = prefs(ctx).getString(id, null) ?: return
        prefs(ctx).edit().remove(id).apply()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tenggat", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = o.optString("text")
        nm.notify(
            id.hashCode(),
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mythos)
                .setContentTitle(o.optString("title"))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }
}

class DeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DeadlineNotices.ACTION_FIRE -> DeadlineNotices.fire(context, intent.getStringExtra("id") ?: return)
            Intent.ACTION_BOOT_COMPLETED -> DeadlineNotices.rearm(context)
        }
    }
}
