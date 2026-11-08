# v68: pemilihan model otomatis ikut menguji panggilan alat

Dasar temuan: rekaman 1000244592. Uji lama ("Reply with exactly: ok") hanya membuktikan model menjawab, bukan bahwa ia bisa jadi agen.

- `ModelCatalog.kt`: `ModelScanner` punya parameter `checkTools` (bawaan mati). Bila menyala, model yang lolos uji "ok" di `findFirst` diuji sekali lagi: satu permintaan kecil (512 token) yang meminta panggilan `Read`, diurai dengan `Protocol.parse` yang sama seperti saat tugas (`toolProbe`, `toolProbeOk`).
- Yang tidak menulis panggilan `Read` (menjawab prosa, alat lain, atau tak terbaca) diturunkan menjadi BUSY dengan keterangan "tidak menulis panggilan alat dengan format yang benar", lalu model berikutnya diuji. Ia tetap ada di daftar sebagai cadangan terakhir, jadi tugas tidak gagal bila semua model gagal uji ini.
- Galat jaringan/kuota/balasan kosong pada uji alat = tidak meyakinkan, dianggap lolos.
- `ModelResolver.shared` (yang dipakai aplikasi) menyalakan `checkTools`. Hasilnya ikut di-cache 15 menit, jadi biayanya satu permintaan tambahan per sesi per model yang lolos uji pertama. Penyedia berkunci (OpenRouter) tidak dipindai, jadi tidak terpengaruh.
- `scan()` (daftar lengkap) tidak berubah.
- Tes baru: `ToolProbeTest`.
- `versionCode` 68 / `versionName` 1.0.68.

Belum dikompilasi dan belum dijalankan di perangkat.
