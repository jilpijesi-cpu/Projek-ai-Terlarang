# v44 — Baris aktivitas muncul tepat setelah efek mengetik selesai
- Sebelumnya antrean menunggu perkiraan waktu (panjang teks / kecepatan), sehingga bisa meleset dari ketikan yang sebenarnya.
- Sekarang tampilan teks melapor ke `RevealClock` saat ketikan benar-benar selesai (teks tidak lagi mengalir + semua huruf terlihat); antrean melepas blok berikutnya `TEXT_PAUSE_MS` (300 ms) sesudahnya (`releaseReady`).
- Batas pengaman: bila tak pernah ada laporan (blok tergulir keluar layar), dilepas setelah perkiraan + 3 dtk.
- Pudar ujung teks sesudah ketikan 500 -> 300 ms, agar teks sudah solid saat baris aktivitas muncul.
- Tes baru di PaceTest (`releaseReady`). Belum dikompilasi: jalankan `./gradlew testDebugUnitTest`.
- Perbaikan lanjutan: baris aktivitas TAMBAHAN (di bawah narasi, saat model menulis alat atau narasi sudah final) dulu muncul begitu narasi selesai DITERIMA, bukan selesai DIKETIK. Kini `needsExtraLive(textSettled)` menunggu ketikan narasi terakhir selesai + 300 ms.
- Urutan berbasis kejadian, bukan jadwal: STEPS_HOLD_MS 1400 -> 500 ms (hanya agar label sempat terbaca). Lama tiap narasi mengikuti panjang/aliran teksnya; blok berikutnya muncul saat ketikan selesai. `revealDurationMs` kini hanya cadangan batas pengaman.
