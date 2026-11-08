# v33 — tiga penyedia baru + logo

## Penyedia baru (Pengaturan, pemilih model, Otomatis tidak berubah)
1. **Hugging Face** — Inference Providers serverless lewat `https://router.huggingface.co/v1` (kompatibel OpenAI; endpoint lama
   `api-inference.huggingface.co` sudah ditinggalkan). Token `hf_…` dengan izin "Make calls to Inference Providers". Daftar model dipindai seperti penyedia lain.
2. **GitHub Models** — `https://models.github.ai/inference`; daftar model dari `/catalog/models` (larik JSON, kini didukung `parseModelIds`).
   Token GitHub (PAT) dengan izin `models:read`. Kolom token ini terpisah dari token build GitHub Actions.
3. **OpenRouter** — model **dikunci** ke `openrouter/free`: tanpa daftar/pemindaian, kolom model disembunyikan, isian lama diabaikan.

## Logo
`ui/ProviderLogos.kt` + `res/drawable/ic_provider_{github,huggingface,openrouter}.xml`, tampil di chip Pengaturan dan baris "Pilih model".
Untuk berkas resmi: taruh gambar bernama sama di `res/drawable` (hapus .xml-nya bila gambar PNG/WebP). Tanpa perubahan kode.

## Lainnya
`versionCode` 3. Tes baru: `NewProvidersTest`. Belum dikompilasi (lihat CHANGELOG-v31).

## Penjelajahan web untuk agen
- `WebSearch {query, max_results}` — mencari lewat DuckDuckGo (tanpa kunci API); aman, jalan tanpa persetujuan, boleh di mode rencana.
- `WebFetch {url, offset, max_chars}` — mengunduh satu halaman, mengubah HTML menjadi teks (judul, daftar, tautan), membaca bertahap lewat `offset`.
  Meminta persetujuan per URL (mode ACCEPT_EDITS otomatis), karena URL bisa dipakai membocorkan data lewat query string.
- Pengaman: hanya http/https, tanpa kredensial di URL, alamat lokal/privat/link-local/CGNAT ditolak (dicek setelah DNS dan di tiap redirect), maks. 5 redirect, 2 MB unduhan, PDF/gambar ditolak.
- Isi web diberi label "data tak tepercaya" dan prompt melarang mengikuti perintah di dalamnya atau memasukkan isi proyek/rahasia ke query/URL.
- Tes: `WebTest`. Belum dikompilasi.

## Jawab dulu, baru kerja
- Prompt: balasan pertama tiap tugas wajib diawali satu-dua kalimat pembuka sesuai permintaan ("Siap, saya akan membuatkan aplikasi kalkulator yang bagus dan canggih."),
  lalu pada balasan YANG SAMA langsung memanggil alat (jadi teks tampil dulu, kerja menyusul, tanpa menunggu "ya, lanjutkan").
- Penjaga di `Agent`: bila model tetap langsung memanggil alat tanpa kalimat pembuka, aplikasi menyisipkan "Baik BOS, saya mulai mengerjakannya." (sekali per tugas).
  Dapat dimatikan lewat `AgentConfig(ackFirst = false)`. Tes: `AckFirstTest`.

## Teks langsung (baris aktivitas)
- Saat alat sedang ditulis/berjalan, baris berkilau kini menampilkan PENJELASAN dari narasi model (dipadatkan jadi satu baris, tanda Markdown dibuang; tampilan memotong dengan "…"
  bila terlalu panjang). Narasi pendek tampil pendek. Tanpa narasi, tetap judul alat ("Membaca A.kt"); menunggu model tetap "Proses berpikir".
- Prompt meminta kalimat pertama narasi konkret dan jelas bila dipotong jadi satu baris. Tes: `LiveLabelTest`.

## Pemeriksaan bug tampilan (baca kode, belum dijalankan)
- Logo GitHub (#181717) tak terlihat di chip penyedia yang terpilih (latar gelap) → logo kini di lencana putih bulat.
- Baris "Pilih model": subtitle panjang membungkus ke 2 baris di kotak 56 dp dan menabrak tanda centang → satu baris + elipsis, ruang kanan disisakan, subtitle penyedia baru dipendekkan.
- Logo tidak lagi punya contentDescription sendiri (TalkBack membacakan nama dua kali).
- Detail langkah yang sedang dibuka menutup sendiri saat alat selesai (judul kehilangan "…" dan memicu reset) → kunci `remember` memakai judul tanpa "…".
- Dialog "Agen bertanya": pertanyaan panjang terpotong → kini bisa digulir (maks. 360 dp).
