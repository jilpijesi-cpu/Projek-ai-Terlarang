# v61 — kunci ganda untuk semua penyedia
Sebelumnya hanya mode Otomatis (OpenRouter) yang bisa memakai banyak kunci. Sekarang setiap penyedia ber-kunci (OpenAI, Claude, Gemini, DeepSeek, Groq, Hugging Face, GitHub Models, OpenRouter) punya bagian **Kunci tambahan** di Pengaturan.
- Tempel satu atau banyak kunci sekaligus (baris baru/spasi/koma/titik koma); duplikat dan yang bentuknya bukan kunci diabaikan. Disimpan terenkripsi (Keystore), terpisah per penyedia (`SettingsStore.loadExtraKeys/saveExtraKeys`).
- Saat berjalan, tiap model dicoba dengan kunci utama, lalu kunci tambahan, baru pindah ke model berikutnya (`ExtraKeys.expand`). Kunci yang ditolak (401/403) dinonaktifkan dan yang kena batas laju (429) diistirahatkan oleh `KeyPool`/`FailoverClient` seperti biasa.
- Kunci utama tetap wajib diisi; kunci tambahan hanya cadangan. Mode Otomatis tidak berubah (daftarnya tetap terpisah).
- Tes: `ExtraKeysTest`.
