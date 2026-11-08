# v40 — Kunci tambahan untuk mode Otomatis
- +7 kunci OpenRouter (model openrouter/free) dan +1 kunci Groq di EmbeddedKeys.kt.
- Failover sudah bawaan KeyPool: kunci yang gagal (401/403/404 dinonaktifkan; 429/5xx/402 diistirahatkan) otomatis dilewati ke kunci berikutnya, dan kembali ke yang berprioritas lebih tinggi setelah pulih.
- KeySpec.locked: kunci OpenRouter dikunci ke `openrouter/free` dan tidak lagi diperiksa/diganti otomatis ke model lain saat pemindaian awal.
- Perbaikan: deklarasi xmlns:aapt Gemini dipindah ke elemen akar (aman untuk AAPT2).
