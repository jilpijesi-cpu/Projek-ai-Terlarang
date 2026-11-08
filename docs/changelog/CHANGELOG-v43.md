# v43 — Label aktivitas berganti tiap alat (seperti rekaman)
- Penyebab label "tidak berubah-ubah": selama alat berjalan, label selalu memakai SATU narasi model untuk seluruh kelompok, jadi tetap sama walau 4-5 file dibaca berurutan.
- Sekarang: model menulis alat -> narasi; alat berjalan -> judul alat itu ("Membaca A.kt" -> "Membaca B.kt"); menunggu model -> "Proses berpikir".
- Tahan label minimal 700 -> 500 ms agar judul alat yang singkat (±0,5 dtk pada rekaman) sempat terlihat.
- LiveLabelTest disesuaikan + tes baru "Proses berpikir" di sela alat.
Belum dikompilasi: jalankan `./gradlew testDebugUnitTest`.
