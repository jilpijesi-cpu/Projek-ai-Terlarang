# v54 — agen tidak berhenti sebelum berhasil
- Agent.kt: bila build GitHub terakhir gagal, balasan tanpa alat ("Build gagal, saya berhenti...") ditolak dan agen dipaksa membaca log, memperbaiki, lalu build ulang (maks. 8 dorongan). Pertanyaan ke pengguna tetap boleh mengakhiri giliran.
- Agent.kt: bila agen sendiri mengaku "belum berhasil/gagal" tanpa build, didorong lanjut (maks. 3 kali).
- Agent.kt: batas langkah diperpanjang otomatis (+60, maks. 3 kali) selama build masih gagal, bukan lagi berhenti dengan pesan batas.
- Build yang gagal karena konfigurasi (token/repo belum diisi) tidak dihitung sebagai kegagalan yang bisa diperbaiki agen.
- Protocol.kt: aturan PERSISTENCE di prompt sistem.
- Tes baru: PersistenceTest. Belum dikompilasi: jalankan `./gradlew testDebugUnitTest`.
- Layar tidak berkedip lagi saat agen dipaksa lanjut: balasan "menyerah" yang ditolak tidak lagi ditampilkan (tidak di-stream dan tidak dijadikan narasi) selama build masih gagal; hanya satu catatan "Agen melanjutkan memperbaiki sendiri" yang muncul.
