# v63 — Pemilihan model otomatis: satu per satu, berhenti di yang pertama merespons

- **Sebelumnya**: tugas baru mengetes model dalam gelombang paralel (4 sekaligus, sampai 3 siap) dan menunggu seluruh gelombang selesai; satu model yang menggantung menahan sampai 25 detik.
- **Sekarang** (`ModelScanner.findFirst`, dipakai lewat `ModelResolver.firstReady` oleh `AgentViewModel.prepareClient`):
  - model diuji SATU PER SATU: pilihan manual pengguna dulu, lalu urutan perkiraan mutu;
  - model pertama yang merespons langsung dipakai, model lain tidak diuji;
  - kuota/kredit habis, error, atau tidak merespons dalam 10 detik (sebelumnya 25) -> langsung lanjut ke model berikutnya;
  - paling banyak 6 model diuji; 4 model berikutnya ikut sebagai cadangan berstatus `UNTESTED`, baru dicoba `FailoverClient` bila model aktif gagal di tengah tugas;
  - bila tak satu pun merespons, cadangan tetap diberikan, jadi tugas masih mencoba mereka.
- Hasil di-cache 15 menit. Layar Pengaturan memakai jalur dan cache yang SAMA (`AgentViewModel.scanModels` -> `ModelResolver.firstReady`): begitu kunci dimasukkan, aplikasi langsung memindai terbaik dulu dan berhenti di model pertama yang merespons, sehingga tugas pertama sesudahnya langsung jalan tanpa tes ulang. Daftar di Pengaturan kini berisi: model siap, cadangan "belum diuji", dan yang sempat sibuk (bukan lagi daftar lengkap).
- Cakupan: berlaku untuk OpenAI, Claude, Gemini, DeepSeek, Groq, Hugging Face, GitHub Models, dan Ollama. OpenRouter dikecualikan karena modelnya terkunci (`openrouter/free`; pemilihan model dilakukan OpenRouter sendiri, hanya kunci yang bergantian).
- Ollama (HTTP polos lokal): batas tunggu uji 60 dtk, bukan 10, karena model lokal yang belum dimuat ke memori lambat di permintaan pertama (`probeTimeoutFor`).
- **Tombol "Mode model reasoning" dihapus** (sebelumnya hanya ada untuk OpenAI) beserta pengaturan `reasoningMode` di `Settings`. Gantinya, `FailoverClient` menyesuaikan sendiri: bila sebuah model OpenAI-kompatibel menjawab 400 soal `temperature`/`max_tokens`/"unsupported", panggilan diulang SEKALI tanpa temperature dan dengan `max_completion_tokens` (`adaptedForReasoning`), lalu parameter itu diingat untuk entri tersebut. Ini juga berlaku untuk cadangan yang belum diuji dan untuk semua penyedia berformat OpenAI (DeepSeek, Groq, Hugging Face, GitHub Models, OpenRouter, Ollama), bukan hanya OpenAI. Kunci tidak dihukum karena percobaan ulang ini.
- Preferensi lama `reasoning_<penyedia>` di perangkat diabaikan (tidak lagi dibaca).
- Batasan: penyesuaian hanya satu arah (ke tanpa temperature + `max_completion_tokens`); bila sebuah penyedia menolak `max_completion_tokens` pun, entri itu gagal seperti biasa dan diistirahatkan.
- Tes baru di `ModelCatalogTest` (urutan dan berhenti di yang pertama, model pilihan manual, tak ada yang merespons, cache, batas tunggu Ollama) dan `FailoverTest` (penyesuaian reasoning otomatis).
- Versi 1.0.63. Belum dikompilasi: jalankan `gradle :app:testDebugUnitTest`.

## Perbaikan: kunci API terakhir tidak bisa dihapus
- **Penyebab**: tombol Simpan nonaktif selama kunci kosong (`enabled = s.isConfigured`), sedangkan menghapus KUNCI UTAMA hanya mengubah isian di layar dan baru tersimpan lewat Simpan. Jadi kunci terakhir tidak pernah benar-benar terhapus (kembali lagi setelah layar ditutup). Kunci tambahan tidak bermasalah karena tersimpan seketika.
- **Perbaikan**: menghapus kunci utama sekarang tersimpan langsung seperti kunci tambahan (`AgentViewModel.removeMainKey` -> `SettingsStore.saveMainKey`, hanya menulis kunci penyedia itu, tidak mengubah penyedia aktif/model). Bila ada kunci tambahan, yang pertama naik jadi utama; bila tidak, kunci dikosongkan. Dialog konfirmasi kini menyebut "sekarang juga".
- Sebelumnya, menghapus kunci utama saat ada kunci tambahan menghapus kunci cadangan itu dari penyimpanan seketika tetapi kunci utama lama baru berganti setelah Simpan; keduanya kini konsisten. Daftar kunci juga tidak menampilkan kunci yang sama dua kali.

## Keyboard menutup otomatis setelah pesan terkirim
- `MythosInputCard` (dipakai layar beranda dan layar obrolan) kini menutup keyboard dan melepas fokus kolom input begitu tombol Kirim menekan pengiriman sungguhan (ada teks atau lampiran). Bila kolom kosong tanpa lampiran, tidak ada yang terkirim dan keyboard dibiarkan terbuka.
- Tidak berubah: tombol Hentikan, mikrofon, tambah lampiran, dan pemilih model.
