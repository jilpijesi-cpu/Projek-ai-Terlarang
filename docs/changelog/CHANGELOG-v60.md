# v60 — jejak alat tidak bocor lagi + perbaikan hasil audit
- **Parser** (`Protocol.kt`): JSON polos `{"status":..,"name":"LS","input":{..}}` kini dikenali sebagai pemanggilan alat, disembunyikan saat streaming, `status` jadi label langkah; `<invokename="X">` (tanpa spasi) ikut diurai. Tes: `BareJsonCallTest`.
- **Persetujuan workflow** (`Agent.kt`): dialog menampilkan isi workflow UTUH (sebelumnya dipotong 1.500 karakter sehingga langkah berbahaya bisa tersembunyi). Workflow > 20.000 karakter ditolak agar tetap bisa ditinjau.
- **Redaksi rahasia** (`GitHubActions.kt`): ditambah Hugging Face, Tavily, Brave, DeepSeek, `ghs_/gho_/ghu_`. Tes: `SecretRedactionExtraTest`.
- **Privasi** (`Web.kt`): fallback pembaca r.jina.ai tidak lagi dipakai untuk URL ber-query/fragment (bisa memuat token).
- **Versi**: `versionCode 60`, `versionName 1.0.60` (sebelumnya 52, tidak sinkron dengan changelog).
- `EmbeddedKeys.kt` di paket ini adalah stub kosong; kunci lama harus dicabut.
