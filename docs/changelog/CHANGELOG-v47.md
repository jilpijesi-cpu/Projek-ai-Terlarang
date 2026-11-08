# v47 — Audit Teknis Maksimal & Perbaikan Menyeluruh

## 1. Perbaikan Galat Kompilasi & Kegagalan Uji Unit
- **`Protocol.kt:188` (Fatal Compilation Error):** Tanda kutip ganda (`"Saya menulis layar kalkulator..."`) yang tidak di-*escape* di dalam literal string `Protocol.systemPrompt` diperbaiki menjadi `\"...\"`.
- **`Timeline.kt` (`narrationForLive`) vs `TimelineTest`:** `narrationForLive` sebelumnya mengambil `blocks[size - 2]` saat blok terakhir adalah `StepsBlock`, sehingga narasi lama dari langkah sebelumnya terbaca ulang ketika model mulai mengalirkan pemanggilan alat baru tanpa narasi (dan menggagalkan `TimelineTest.liveLabelPrefersPreparingToolThenRunningToolThenThinking`). Kini hanya memakai `blocks.lastOrNull() as? TextBlock`.
- **`Agent.kt` (`loop` & `streamReply`) vs `AgentStreamingTest`:**
  - Ketika `reply.text` kosong (mis. pemanggilan alat teks polos `AskUserQuestion: {...}`), agen selalu memancarkan `AgentEvent.Streaming("")` lebih dulu untuk membersihkan pratinjau sementara sebelum `Thought`/`ToolStart`, dan tidak menyisipkan kalimat pembuka otomatis (`ackFirst`) bila pemanggilan alat hanya berisi `AskUserQuestion`.
  - Korutin konsumen `streamReply` dijalankan dengan `CoroutineStart.UNDISPATCHED` agar peristiwa `Streaming` awal tidak tertimpa di `Channel.CONFLATED` sebelum korutin konsumen mulai berjalan.

## 2. Perbaikan Keamanan (Security Hardening)
- **Pencegahan Tag Breakout / Prompt Injection (`Protocol.kt`):** `Protocol.result` kini meng-*escape* tag penutup `</tool_result>` di dalam badan hasil alat menjadi `<\/tool_result>`, mencegah isi berkas/halaman web tak tepercaya menutup blok `<tool_result>` lebih awal dan menyuntikkan `<system-reminder>` palsu atau merusak regex pemadatan konteks (`ContextManager`).
- **Penguatan Perlindungan SSRF (`Web.kt`):** `isPrivateAddress` kini juga memblokir alamat IPv6 6to4 (`2002::/16`) dan NAT64 (`64:ff9b::/96`) yang menyematkan alamat IPv4 lokal/privat, serta rentang IPv4 *benchmarking* (`198.18.0.0/15`), *protocol assignment* (`192.0.0.0/24`), dan *broadcast* (`255.255.255.255`).
- **Validasi Skema Redirect Log GitHub (`GitHubActions.kt`):** `fetchLog` memastikan URL `Location` redirect diawali `https://` atau `http://` sebelum diikuti, dan `branchFor` menghapus akhiran `.lock` secara berulang di dalam loop.
- **Pemulihan Kunci Keystore & Pemeriksaan Batas Dekripsi (`Settings.kt`):** `SecretBox.decrypt` memvalidasi panjang minimal IV GCM (> 12 byte), dan `SecretBox.encrypt` otomatis membuat ulang kunci `AndroidKeyStore` bila kunci lama mengalami invalidasi permanen (`KeyPermanentlyInvalidatedException`).

## 3. Perbaikan Stabilitas, Memori, Concurrency & Kompatibilitas Android
- **Failover Cepat pada Permintaan 400/413/422 (`KeyPool.kt`):** `FailoverClient` kini langsung mengampuni (`forgive`) dan menghentikan percobaan bila seluruh kunci yang siap pakai menolak permintaan yang sama (`pool.pick() == null`), tanpa tidur 45 detik berulang kali saat ada kunci lain yang sedang menjalani *cooldown* kuota harian.
- **Pencegahan OOM & Kebocoran Memori Bitmap (`Attachments.kt`, `Checkpoints.kt`, `Tools.kt`):**
  - `Attachments.commit` tidak lagi memanggil `target.readBytes()` (hingga 25 MB) hanya untuk membaca 8 KB pertama pada deteksi teks (`readHead`).
  - `writeScaledJpeg` dan `renderPdf` memanggil `Bitmap.recycle()` pada setiap bitmap sementara/hasil kompresi serta mendukung orientasi EXIF *flip/transpose*.
  - `Checkpoints.sameBytes` membandingkan berkas secara *streaming* per blok 8 KB alih-alih mengalokasikan dua array byte 10 MB sekaligus.
  - `Write` dan `Edit` memeriksa `rel in env.session.known`, batas ukuran berkas (`MAX_TEXT_FILE_BYTES`), dan status biner sebelum memanggil `f.readText()`.
- **Perbaikan Race Condition Penghapusan Riwayat & Pembersihan Cache (`AgentViewModel.kt`, `Timeline.kt`):**
  - `deleteChat` membatalkan `persistJob` dan memegang `persistLock` saat menghapus berkas riwayat agar penyimpanan latar belakang yang sedang berjalan tidak menghidupkan kembali obrolan yang baru dihapus.
  - `cleanIncognito` turut membersihkan folder sementara `staged`, `camera`, dan `exports` di `cacheDir` saat proses baru dimulai, dan `RevealClock.reset()` membersihkan peta statis saat berganti percakapan.
- **Layanan Latar Android 14 (`AgentService.kt`, `MainActivity.kt`):**
  - Menambahkan `onTimeout(startId: Int)` untuk kepatuhan `foregroundServiceType="dataSync"` di Android 14 (SDK 34), memperbarui masa berlaku `WakeLock` pada setiap `onStartCommand`, memakai `applicationContext` pada `Handler` penunda penghentian, dan melindungi peluncur `Intent` (`shareZip`, kamera/dokumen) dari `ActivityNotFoundException`.
- **Normalisasi Model & Bagian Thought Gemini (`Llm.kt`, `ModelCatalog.kt`):**
  - `GeminiClient` menghapus awalan `models/` ganda pada URL, `geminiWhole` melewati bagian `"thought": true` seperti `geminiDelta`, dan `ModelScanner.scan` menghitung ulang `maxOutputTokens` untuk setiap kandidat model.
- **Perbaikan Glob/Grep, Delete & Resource (`Tools.kt`, `res/`, `proguard-rules.pro`):**
  - `Glob` dan `Grep` menghapus awalan `./` sebelum memeriksa keberadaan `/` pada pola glob; `Delete` mengembalikan `readPaths` agar berkas yang dihapus keluar dari daftar `dirty`.
  - Merapikan presisi float path vektor `ic_provider_anthropic.xml`, memakai `@color/mythos_accent` di `ic_launcher_foreground.xml`, mengisi aturan `proguard-rules.pro`, dan menggabungkan `app/CHANGELOG-v45.md` ke `CHANGELOG-v45.md` di akar proyek.

### 15. `AgentViewModel.kt` — kehilangan konteks lampiran lintas-turn & baris "Proses berpikir" palsu pada sapaan
- **Masalah:**
  1. Saat pengguna melampirkan file/ZIP, `Attachments.noteBlock(committed)` hanya ditempelkan ke `task` pada turn saat itu, tetapi `handle(e, prompt)` menyimpan `prompt` mentah tanpa `noteBlock` ke `history`. Akibatnya, pada turn berikutnya (mis. `"itu file itu"`), agen kehilangan informasi path lampiran (`uploads/...`) dan bertanya `"BOS, mau lihat file mana?"`.
  2. Pada turn pertama (`firstTurn`), `send()` selalu menyisipkan `NoticeItem("Build GitHub Actions belum diatur...")` sebelum agen berjalan, sehingga sapaan sederhana seperti `"halo"` tetap menampilkan baris langkah `Proses berpikir >`.
- **Perbaikan:** Menyimpan `historyPrompt = prompt + noteBlock` ke `history`, memperbesar batas cuplikan `history` di `buildTask` menjadi `1_500` karakter, serta menunda `NoticeItem` peringatan GitHub Actions hingga agen benar-benar memanggil tool `Write` atau `Edit`.

### 16. `Protocol.kt`, `Agent.kt`, & `Attachments.kt` — eksekusi langsung perintah audit pada file lampiran
- **Masalah:** Ketika pengguna mengirim kata singkat seperti `"audit"` bersama lampiran ZIP pada model gratis (`openrouter/free`), model balik bertanya fokus audit alih-alih langsung memeriksa isi `uploads/`, ditambah kedalaman pohon direktori (`maxDepth = 3`) belum menembus folder pembungkus di dalam ZIP (`uploads/Mythos-Terlarang/MythosV6/...`).
- **Perbaikan:** Mempertegas `Protocol.systemPrompt` agar perintah `"audit"`, `"cek"`, `"periksa"`, `"analisis"`, `"perbaiki"`, atau rujukan `"itu file itu"` langsung mengeksekusi tool (`Glob`/`LS`/`Read`) pada `uploads/` tanpa balik bertanya, serta menaikkan kedalaman `renderTree` menjadi `5` level.

### 17. `ModelCatalog.kt` — duplikasi entri `KeyPool` akibat ekspansi kandidat model per API key
- **Masalah:** `ModelScanner.resolveSpec` mengembalikan seluruh kandidat model (`usable.map { spec.with(it) }`) untuk satu API key tanpa model tetap, sehingga 15 key bawaan membengkak menjadi 38 entri di `KeyPool` (`30/38`).
- **Perbaikan:** Membatasi `resolveSpec` agar memilih 1 model terbaik yang lolos verifikasi (`listOf(spec.with(usable.first()))`) per slot API key.

### 18. `MascotAnimation.kt` & `Design.kt` (`PixelMascot`) — animasi maskot piksel berkelanjutan 1:1 sesuai video referensi
- **Fitur:** Mengubah `PixelMascot` statis di layar beranda menjadi animasi berkelanjutan yang memutar 3 rangkaian gerakan (total 41 pose pada kisi setengah-sel `24 x 16`, diekstrak 1:1 dari rekaman video referensi):
  1. Menengok & melangkah 3D ke kanan lalu ke kiri dengan bayangan kedalaman sisi (`#B55C40`).
  2. Mengedipkan mata, mengeluarkan tongkat piksel (`#8A8A8A`), lalu mengayun/menyodok ke tanah 6 kali sebelum menyimpannya kembali.
  3. Merunduk, melompat tinggi sambil mengangkat kedua tangan ke atas kepala, lalu mendarat dan memantul.
- **Interaksi:** Selain bergerak otomatis secara terus-menerus, maskot juga dapat diketuk untuk langsung melompat ke rangkaian gerakan berikutnya.
- **Pengujian:** Menambahkan `MascotAnimationTest.kt`.
