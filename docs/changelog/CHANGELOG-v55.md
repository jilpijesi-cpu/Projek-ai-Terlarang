# v55 — pembungkus <xxx_function_call> tidak lagi tampil mentah
Rekaman: model mengirim `<dots_function_call>...</dots_function_call>` (format yang tidak dikenal parser). Akibatnya tahap 1 alur streaming (kenali alat) gagal, sehingga tahap berikutnya (label aktivitas, ToolStart/ToolEnd) tidak pernah jalan dan teks mentah tampil sebagai jawaban.
- Protocol.kt: pembungkus apa pun berakhiran `function_call(s)` / `tool_calls` dibuka: kosong dibuang, isi JSON jadi tool_use, isi format GLM/`<function=>` diteruskan ke normalizer yang ada.
- Protocol.kt: pembungkus disembunyikan saat streaming (termasuk awalan separuh "<dots_func"), label aktivitas dibaca dari nama alat di dalamnya, penutup yatim dibuang.
- Tes baru di ArgKvToolCallTest. Belum dikompilasi: jalankan `./gradlew testDebugUnitTest`.
