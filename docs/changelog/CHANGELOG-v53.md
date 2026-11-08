# v53 — agen tidak lagi menampilkan panggilan alat mentah, push tidak membawa kunci
- Protocol.kt: format GLM `<tool_call>Nama<arg_key>k</arg_key><arg_value>v</arg_value>` dikenali dan dijalankan (penutup boleh hilang; beberapa panggilan berurutan).
- Protocol.kt: markup karangan model (`<|tool_call|><|begin_of_content|>...`, `<arg_key>` yang gagal diurai) disembunyikan saat streaming dan, bila tanpa panggilan sah, jadi Invalid sehingga agen diminta menulis ulang, bukan tampil sebagai jawaban.
- GitHubActions.kt: push menghormati `.gitignore` proyek dan mengganti kunci API (sk-or-v1-, sk-proj-, gsk_, nvapi-, AIza, AQ., ghp_ dst.) dengan REDACTED di salinan yang dikirim. Penyebab HTTP 422 "Secret detected".
- Agent.kt: dorongan "lakukan, jangan umumkan" naik dari 2 ke 4 kali per tugas.
- EmbeddedKeys.kt dikosongkan. Semua kunci yang pernah ada di berkas itu harus dicabut dan dibuat ulang.
- Tes baru: ArgKvToolCallTest, SecretRedactionTest.
Belum dikompilasi/diuji (tidak ada Gradle di sini): jalankan `./gradlew testDebugUnitTest`.
