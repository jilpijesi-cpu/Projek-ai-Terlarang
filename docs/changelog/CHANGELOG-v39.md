# v39 — Tempo streaming santai & berurutan
- Teks mengalir berbasis waktu (±42 huruf/detik), tidak lagi mengejar jaringan; hanya dipercepat bila antrean menumpuk.
- Antrean tampil: narasi selesai terketik + jeda 0,8 dtk, kelompok langkah tampil min. 1,5 dtk, baru blok berikutnya muncul.
- Label aktivitas ditahan 1,1 dtk; animasi muncul/ganti label diperlambat; ketikan judul langkah lebih pelan.
- Konstanta tempo ada di Timeline.kt (REVEAL_CPS, TEXT_PAUSE_MS, STEPS_HOLD_MS, TAIL_HOLD_MS).
