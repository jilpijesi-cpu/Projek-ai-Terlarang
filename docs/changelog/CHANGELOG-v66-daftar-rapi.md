# v66: daftar (butir) tampil rapi, tulisan tidak bertabrakan

Dasar temuan: tangkapan layar 1000244585 dan rekaman 1000244584.

- Penyebab tulisan terlihat "miring/tidak rapi": butir daftar (`•` dan `1.`) hanya teks biasa. Baris pertama menjorok, baris lanjutannya rata kiri di bawah penanda, jadi membentuk tangga. Kini `AnswerBody` memecah teks menjadi paragraf dan butir (`splitProse`); tiap butir ditampilkan sebagai penanda berlebar tetap + isi di sebelahnya, sehingga baris lanjutan sejajar dengan isi (indentasi menggantung). Tingkat menjorok diambil dari spasi di depan baris (16 dp per tingkat, maksimum 3).
- Jarak: antar butir 3 dp, antara paragraf dan butir 8 dp.
- `ChatScreen.kt`: animasi geser 200 ms pada blok teks (dari v65) dihapus karena membuat teks sesaat menimpa baris "Menimbang langkah berikutnya". Baris aktivitas tetap memudar 160 ms.
- Tes baru di `CopyBlocksTest`: pemecahan butir dan tingkat menjorok, serta teks tebal di awal baris (`**Tebal**`) bukan butir.
- Belum dikompilasi dan belum dilihat di perangkat.
