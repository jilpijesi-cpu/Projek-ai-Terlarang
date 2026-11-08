# v75 - Kartu draf email, kartu PDF, notifikasi tenggat, perilaku agen lebih ketat

Menggantikan perilaku `ComposeEmail` yang dijelaskan di CHANGELOG-v70.

## Email sebagai kartu di chat
- `ComposeEmail` kini hanya memvalidasi lalu menampilkan KARTU draf (Kepada, Cc, Subjek, Isi, lampiran PDF, tombol "Kirim email"). Alat TIDAK membuka aplikasi email dan TIDAK mengirim.
  Aplikasi email baru dibuka saat pengguna mengetuk "Kirim email" (`AgentViewModel.openEmailDraft` -> `AndroidDeviceActions.composeEmail`); pengguna masih menekan Kirim sendiri di sana.
- Risiko alat turun ke `Risk.SAFE` (tanpa dialog persetujuan) karena kartu itu sendiri adalah peninjauannya.
- Event baru `AgentEvent.EmailDraft` -> `EmailDraftItem` (disimpan di riwayat, kunci `"email"` di ChatStore).
- Penjaga kode: badan atau subjek email yang memuat angka 16 digit (tampak seperti NIK) ditolak; agen diminta menyamarkan atau merujuk ke lampiran.

## Kartu PDF
- `PdfFileCard` (ikon PDF, nama tanpa ekstensi, "Dokumen · PDF", tombol bagikan) dipakai di kartu email dan di ringkasan perubahan; menggantikan `PdfFileRow` dan `FileActionButton`.

## PDF
- Penomoran buatan model di awal item ("1. Foto KTP", "2)", "(3)", "- ") dibuang oleh parser (`stripListMarker`), karena penanda digambar oleh `PdfPlanner`. Memperbaiki nomor ganda "1. 1.".

## Notifikasi tenggat
- Alat `SetDeadlineNotice` (judul, `at`, catatan, `remind_before_minutes`) -> `DeviceActions.scheduleNotice` -> `DeadlineNotices` (AlarmManager tak-presisi + SharedPreferences, dipasang ulang setelah boot lewat `DeadlineReceiver`).
- Manifest: `RECEIVE_BOOT_COMPLETED` dan receiver `.ui.DeadlineReceiver`.

## Aturan prompt
Satu pertanyaan per giliran dalam sengketa; NIK tidak diulang di badan email; alamat resmi penerima dicari dulu; agen tidak mengulang isi email di jawabannya.

## Pembersihan
Dihapus: impor `Canvas` yang tak terpakai (`PdfEdit.kt`), `private val Line` yang tak terpakai (`Workspace.kt`), `PdfFileRow`, `FileActionButton`.

## Belum diuji
Kode ditulis tanpa kompilator di lingkungan ini. Jalankan `./gradlew test` atau push ke GitHub Actions (ci.yml) sebelum dipakai.

## Perbaikan: 429 "limit: 0" tidak lagi ditunggu berulang
- Google AI Studio membalas `429 ... limit: 0` untuk model tanpa jatah gratis. Pesannya memuat "per minute", sehingga dulu dianggap batas per menit (istirahat 60 detik lalu diulang) dan tugas menggantung beberapa menit.
- `isZeroQuota` (KeyPool.kt), juga dipakai `RetryingClient` (Llm.kt, jalur tanpa failover): model seperti itu diistirahatkan 6 jam dan dibuang dari daftar saat pemindaian (`ModelCatalog.kt`), sehingga cadangan langsung dipakai. Tes: `FailoverTest.zeroQuota429IsLongRestNotAMinuteRetry`.
