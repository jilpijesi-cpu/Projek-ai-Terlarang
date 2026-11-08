# v51
- Protocol.kt: format `<tool_call>{...}</tool_call>` (Hermes/Qwen) dikenali dan diubah ke blok `tool_use`; `arguments`/`parameters` (termasuk string JSON) diterima;
  blok disembunyikan saat streaming; `</tool_call>` sisa dan baris `}`/`]` yatim di luar pagar kode tidak lagi tampil. 10 tes baru di ProtocolTest.
- Manifest: `usesCleartextTraffic` diganti `network_security_config` (HTTP polos hanya ke localhost, 127.0.0.1, 10.0.2.2). WebFetch ke situs `http://` kini gagal; pakai `https://`.
- Build release: `isMinifyEnabled` dan `isShrinkResources` aktif; CI membangun `assembleRelease` untuk menangkap kesalahan R8.
- versionCode 3 -> 51, versionName 1.0 -> 1.0.51.
