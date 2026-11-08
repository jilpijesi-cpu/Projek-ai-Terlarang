# v70 - Mengurus pengaduan seperti asisten percakapan

Tambahan, tanpa mengubah perilaku lama.

## Alat baru (aktif bila aplikasi menyediakan `DeviceActions`)
- `ComposeEmail`: membuka aplikasi email dengan draf lengkap (kepada, cc, subjek, isi, lampiran PDF dari proyek).
  TIDAK PERNAH mengirim sendiri: pengguna menekan Kirim. Selalu minta persetujuan (Risk.DANGEROUS); dialog menampilkan penerima, subjek, awal isi, lampiran.
  Satu panggilan = satu email, jadi draf untuk BI dan OJK tetap terpisah. Lampiran dibagikan lewat FileProvider (`files-path projects/`).
- `SetReminder`: alarm (jam) atau acara kalender untuk tenggat; layar aplikasi tujuan terbuka dan pengguna yang menyimpan.
  Izin baru di manifest: `com.android.alarm.permission.SET_ALARM` (izin normal).

## Memori kasus
- `KASUS.md` di akar proyek (tiket, linimasa klaim bertanggal, bukti, tenggat, apa yang sudah dikirim) dimuat otomatis di pesan pertama tiap tugas, bersama `CLAUDE.md`.

## Aturan perilaku di prompt (bagian "Complaints, disputes and official letters")
Berkas kasus, deteksi pertentangan (bahasa "tidak konsisten", bukan tuduhan), siapa pengadu, urutan tindakan (jangan menulis "sudah dikirim" sebelum benar),
kanal resmi dicek lewat WebSearch/WebFetch, pesan singkat dengan tenggat, draf terpisah per penerima, privasi (KTP hanya bila diminta, sensor data pihak ketiga, risiko UU ITE),
paket bukti dengan MakePdf, serta aturan ComposeEmail/SetReminder.

## Berkas
`agent/DeviceActions.kt`, `agent/CaseTools.kt`, `ui/AndroidDeviceActions.kt`, `CaseToolsTest.kt`; diubah: `Tools.kt` (ToolEnv.device, registry), `Agent.kt`, `MythosAgent.kt`, `Protocol.kt`, `AgentViewModel.kt`, `AndroidManifest.xml`.

## Belum diuji
Kode ditulis tanpa kompilator di lingkungan ini. Jalankan `./gradlew test` atau push ke GitHub Actions (ci.yml) sebelum dipakai.
