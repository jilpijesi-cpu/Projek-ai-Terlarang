# v45 — Baris langkah selesai mempertahankan teks labelnya
Alur: label hidup (titik oranye) -> saat ketikan narasi MULAI, label berubah jadi ikon jam DENGAN teks label yang sama -> saat ketikan SELESAI, label baru muncul di bawahnya -> dst.
- Sebelumnya baris yang selesai memakai judul ringkasan ("Membaca 4 file") dan teks label hilang. Kini `finishedLabel()` memakai label hidup terakhir ("Proses berpikir" dilewati; riwayat lama tetap memakai judul kelompok).
- Peninjauan ulang: (1) `RevealClock` dibersihkan saat blok teks dilepas, karena id item dimulai ulang per percakapan (`counter = saved.items.size`) dan catatan lama bisa membuat blok berikutnya muncul terlalu cepat; (2) status "narasi selesai" untuk baris tambahan dihitung ulang terus (tidak lagi dikunci) agar dicabut bila teks bertambah; (3) komentar tes yang nyasar dirapikan.
- Tes baru di LiveLabelTest. Belum dikompilasi: jalankan `./gradlew testDebugUnitTest`.
