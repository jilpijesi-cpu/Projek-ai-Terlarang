package com.mythos.app.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import com.mythos.app.agent.DeviceActions
import java.io.File

/** Aksi ponsel lewat Intent: tidak butuh izin berbahaya, dan pengguna selalu menekan Kirim/Simpan sendiri. */
class AndroidDeviceActions(private val app: Application) : DeviceActions {

    private fun launch(intent: Intent): String? = try {
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        "Tidak ada aplikasi yang bisa menangani aksi ini di ponsel."
    } catch (e: SecurityException) {
        "Android menolak membuka aplikasi tujuan: ${e.message}"
    } catch (e: Exception) {
        "Gagal membuka aplikasi tujuan: ${e.message}"
    }

    override fun composeEmail(to: List<String>, cc: List<String>, subject: String, body: String, attachments: List<File>): String? {
        val intent: Intent
        if (attachments.isEmpty()) {
            intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        } else {
            val uris = ArrayList<Uri>()
            try {
                for (f in attachments) uris.add(FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f))
            } catch (e: IllegalArgumentException) {
                return "Lampiran berada di luar folder yang boleh dibagikan: ${e.message}"
            }
            intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "message/rfc822"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newRawUri("lampiran", uris[0]).also { c -> for (i in 1 until uris.size) c.addItem(ClipData.Item(uris[i])) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        intent.putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
        if (cc.isNotEmpty()) intent.putExtra(Intent.EXTRA_CC, cc.toTypedArray())
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.putExtra(Intent.EXTRA_TEXT, body)
        return launch(Intent.createChooser(intent, "Buka draf email dengan"))
    }

    override fun setAlarm(hour: Int, minute: Int, label: String): String? = launch(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    )

    override fun addCalendarEvent(title: String, startMillis: Long, endMillis: Long, notes: String): String? = launch(
        Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, notes)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
    )

    override fun scheduleNotice(id: String, title: String, text: String, atMillis: Long): String? = try {
        DeadlineNotices.schedule(app, id, title, text, atMillis)
        null
    } catch (e: Exception) {
        "Gagal menjadwalkan notifikasi: ${e.message}"
    }
}
