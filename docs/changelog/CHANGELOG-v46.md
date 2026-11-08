# v46 — Pil model di kolom input menampilkan kunci Otomatis yang sedang dipakai
- Sebelumnya mode Otomatis selalu tampil "Otomatis 15 kunci", sehingga tidak terlihat kunci mana yang bekerja atau apakah pergantian otomatis jalan.
- Sekarang: sebelum dipakai "Otomatis · 15 kunci"; saat/sesudah bekerja "Otomatis · <kunci aktif>" + "<sehat>/15" (mis. "Otomatis · Groq 2  12/15"). Saat sebuah kunci gagal dan pindah ke kunci lain, nama di pil ikut berganti; angka sehat turun bila kunci istirahat/nonaktif.
- `FailoverClient(onUse)` melapor tiap kunci yang dicoba; `KeyPool.healthyCount()`; `AutoStatus` + `autoChip()` (ui/AutoStatus.kt); pil dilebarkan maks. 160 -> 230 dp.
- Tes baru: AutoChipTest, OnUseTest. Belum dikompilasi: jalankan `./gradlew testDebugUnitTest`.
