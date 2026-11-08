# v63b (dipindah dari app/CHANGELOG-v63.md; isinya berbeda dari CHANGELOG-v63.md)


## Pola gerak baru untuk bola oranye (`LoaderDots`)
- Tiga bola oranye penanda "sedang bekerja" kini punya **16 pola** (sebelumnya 7). Pola 0-6 tidak diubah; peralihan antar pola tetap dihaluskan (blend 30%).
- Pola baru (`dotPattern` 7-15): **7 Juggling** (bergantian tukar tempat lewat busur; bola ujung melompat lewat bawah), **8 Bandul Newton** (ujung kiri/kanan terayun dan menghantam), **9 Segitiga klik** (formasi segitiga berputar 120 derajat bertahap sambil mengembang), **10 Pemindai** (sorotan bolak-balik seperti Knight Rider), **11 Ekor komet** (satu bola besar memimpin, dua mengekor makin kecil), **12 Gelombang bandul** (selaras lalu menyebar lalu selaras), **13 Pusaran** (berputar sambil menyedot ke pusat), **14 Ledakan** (menyatu jadi satu bola besar lalu meledak ke formasi segitiga), **15 Baling-baling** (batang tiga bola berputar).
- Siklus penuh kini 24 detik (16 x 1,5 dtk). Pola ditentukan jam bingkai layar, bukan waktu muncul, jadi setiap kali indikator tampil polanya berbeda-beda.
- Tes baru `DotPatternsTest`: semua pola tetap di dalam kanvas 15 x 20 dp, bernilai hingga, jari-jari wajar, pola baru bergerak mulus tanpa lompatan, dan juggling selalu mengisi tiga slot.
- Belum dikompilasi dan belum dilihat di perangkat: jalankan `gradle :app:testDebugUnitTest` dan lihat langsung lewat indikator "bekerja" di layar obrolan.
