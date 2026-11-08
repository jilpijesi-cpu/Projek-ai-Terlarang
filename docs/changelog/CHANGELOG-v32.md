# v32 — perbaikan dari tangkapan layar v31

## Yang terlihat di tangkapan layar
1. Jawaban berupa teks mentah `AskUserQuestion: {"question": ..., "options": [...]}`, tanpa dialog pertanyaan.
2. Tombol panah "ke bawah" muncul padahal jawaban masih mengalir; sisa teks tertinggal di bawah kartu input.
3. Jawaban berbahasa Inggris untuk pesan berbahasa Indonesia.

## Penyebab dan perbaikan
1. **Model menulis pemanggilan alat sebagai teks polos.** Dokumentasi alat di prompt berbentuk `AskUserQuestion {"question": ...}`, dan model
   Groq ("Otomatis Groq") menirunya alih-alih memakai blok `<tool_use>`. Parser tidak menemukan blok, jadi balasan dianggap jawaban akhir.
   Ini bukan akibat streaming; di v30 teks yang sama juga tampil, hanya setelah diketik.
   - `Protocol.parse` mengenali baris utuh `Nama: {json}`, `Nama {json}`, `Nama({json})`, `- Nama: {json}` (nama alat bawaan, JSON valid, tanpa teks
     lain di baris itu) sebagai pemanggilan. Isi pagar kode, kutipan inline, JSON rusak, dan baris dengan teks sesudah objek tetap teks biasa.
   - Pratinjau streaming menyembunyikan baris itu dan memakai label alatnya ("Bertanya kepada Anda"); sisa pratinjau dibuang sebelum alat berjalan.
   - Prompt: pemanggilan hanya sah dalam blok `<tool_use>`; bahasa bawaan Indonesia.
   - Riwayat percakapan menyimpan bentuk kanonik `<tool_use>`, sehingga model belajar format yang benar pada giliran berikutnya.
2. **Gulir otomatis berlomba dengan pertumbuhan teks.** Selesainya gulir otomatis dibaca sebagai "pengguna menggeser menjauh" bila teks bertambah
   tepat sesudahnya, sehingga pengikutan berhenti. Kini hanya gulir oleh jari (DragInteraction) yang mengubah status menempel. Tombol panah dinaikkan
   ke ±28 dp di atas kartu input, sesuai rekaman.
3. Prompt bahasa (lihat di atas).

`versionCode` dinaikkan ke 2 agar APK baru bisa menimpa v31.

## Tes baru
`LoosePlainCallTest` (13 kasus) dan satu tes agen: pemanggilan teks polos dijalankan, teks mentahnya tidak pernah menjadi jawaban akhir maupun pratinjau.

## Belum diverifikasi
Belum dikompilasi (lihat CHANGELOG-v31). Logika pengenalan baris diuji lewat port Java (semua kasus lolos).

## Batas yang tetap ada
Mythos tidak punya alat yang bisa membuka APK (tanpa shell/aapt; manifest di dalam APK berformat biner). Dengan dialog yang kini muncul, model
meminta teks manifest dari Anda. Audit APK sungguhan butuh alat baru (mis. `ApkInfo` berbasis `PackageManager.getPackageArchiveInfo`).
