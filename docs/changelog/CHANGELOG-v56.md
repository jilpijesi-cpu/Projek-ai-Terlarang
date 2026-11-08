# v56 — fallback umum untuk panggilan alat berformat asing
- Protocol.kt: tag apa pun yang namanya mengandung call/tool/function/invoke dan bukan milik protokol ini disembunyikan saat streaming. Pada balasan tanpa panggilan sah, balasan jadi Invalid (dengan nama tag di alasannya) sehingga agen menulis ulang, bukan tampil mentah. Tag di dalam backtick atau pagar kode dianggap teks biasa; HTML biasa (<b>, <div>) tidak disentuh.
- Protocol.kt: format `<function_calls><invoke name="X"><parameter name="k">v</parameter></invoke>` kini dijalankan.
- `<tool_call>` yang tersisa dan gagal diurai pada balasan final dianggap tidak valid.
- Agent.kt: maxFormatRepairs 3 -> 5 agar format salah diberi lebih banyak kesempatan sebelum agen menyerah.
- Tes baru di ArgKvToolCallTest. Belum dikompilasi: jalankan `./gradlew testDebugUnitTest`.
