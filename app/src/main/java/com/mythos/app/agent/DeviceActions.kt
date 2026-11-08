package com.mythos.app.agent

import java.io.File

/**
 * Aksi di ponsel yang butuh Android (aplikasi email, jam, kalender).
 * Implementasi nyata ada di lapisan UI (`AndroidDeviceActions`); null di ToolEnv = tidak tersedia (uji unit, sub-agen).
 * Semua fungsi mengembalikan null bila layar aplikasi tujuan berhasil dibuka, selain itu pesan galat.
 * Membuka layar BUKAN berarti pengguna sudah mengirim/menyimpan: keputusan akhir selalu di tangan pengguna.
 */
interface DeviceActions {
    fun composeEmail(to: List<String>, cc: List<String>, subject: String, body: String, attachments: List<File>): String?
    fun setAlarm(hour: Int, minute: Int, label: String): String?
    fun addCalendarEvent(title: String, startMillis: Long, endMillis: Long, notes: String): String?

    /**
     * Menjadwalkan notifikasi lokal pada [atMillis] (tetap berjalan setelah aplikasi ditutup dan ponsel dihidupkan ulang).
     * [id] yang sama menimpa jadwal lama. Mengembalikan null bila terjadwal, selain itu pesan galat.
     */
    fun scheduleNotice(id: String, title: String, text: String, atMillis: Long): String? = "Notifikasi terjadwal tidak tersedia di sini."
}
