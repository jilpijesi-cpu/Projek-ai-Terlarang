# v67: token palsu model tidak lagi bocor ke layar; PDF bisa dilihat ulang

Dasar temuan: rekaman layar 1000244592 (model: openrouter/free, yaitu router model gratis yang bisa berganti model tiap permintaan).

## Penyebab yang ditemukan
1. **Token palsu bocor.** Model gaya Llama menulis panggilan alat sebagai JSON polos di dalam `<|start_code|>...</|start_code|>`. `Protocol.parse` mengenalinya lewat `looseCalls`, tetapi jalur itu langsung mengembalikan `Reply` tanpa `garbleCut`, sehingga baris JSON hilang dan token di sekitarnya tampil sebagai narasi. (Pratinjau streaming sudah bersih; yang bocor adalah narasi final.)
2. **Riwayat ikut tercemar.** Narasi kotor disimpan ke riwayat dan dikirim lagi ke model berikutnya, sehingga polanya ditiru. Model juga melanjutkan menulis `<tool_result>` karangan (termasuk `[Hasil pencarian web (Bing)...]` palsu) karena tidak ada pemutus aliran.
3. **Agen berputar pada PDF.** `Attachments.manifest()` menyuruh model "lihat ulang dengan ViewImages", tetapi `ViewImages` hanya bisa memuat gambar (BitmapFactory), jadi PDF dari pesan sebelumnya gagal dibaca. Model lalu mencari di web dan memakai sub-agen tanpa hasil.

## Perubahan
- `Protocol.kt`: `cutMarkup` dipakai di jalur `looseCalls`, jalur `<tool_use>`, dan pratinjau streaming. `fakeResultCut` mendeteksi `<tool_result ...>` / `<|...|>tool_result` di awal baris (di luar backtick, pagar kode, dan blok `tool_use` yang belum ditutup). `parse` membuang hasil karangan itu beserta semua sesudahnya, termasuk panggilan yang bergantung padanya.
- `Agent.kt`: `streamReply` memutus aliran begitu penanda hasil karangan muncul dan memakai teks sebelumnya. Dilakukan di sisi klien, bukan parameter `stop`, karena router tidak menjamin semua model mendukungnya.
- `CleanText.kt`: `stripLeaks` memotong dua token `<|...|>` atau lebih (satu token = sebutan biasa, dibiarkan) dan hasil alat karangan, supaya percakapan lama yang sudah tersimpan juga tampil bersih.
- `ViewImages` + `PdfPages.kt` (baru): PDF dirender per halaman (lebar 1400 px). Argumen opsional `pages` (berbasis 1). PDF rusak/terenkripsi menghasilkan pesan yang menyuruh agen memberi tahu pengguna, bukan mencari isinya di web.
- `Attachments.kt`: catatan lampiran dan manifest menyebut `pages`; `hasImages` kini menghitung PDF agar model yang bisa melihat gambar dipilih.
- `CopyBlocks.kt`: spasi depan pada baris paragraf dibuang (mengurangi efek "tangga").
- `versionCode` 67 / `versionName` 1.0.67 (sebelumnya masih 1.0.63).

## Tes baru
`LeakedMarkupTest` (token + JSON polos, hasil karangan, pratinjau streaming, penyebutan/isi file tidak terpotong, pemilih halaman PDF), tambahan di `CleanTextTest` dan `CopyBlocksTest`.

## Belum diverifikasi
Belum dikompilasi dan belum dijalankan di perangkat (tidak ada toolchain Kotlin/Android di lingkungan penulisan). Jalankan `./gradlew testDebugUnitTest` dan uji dengan PDF hasil scan sebelum dipakai.
