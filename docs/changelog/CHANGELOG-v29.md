# v29 — perbaikan hasil audit

Semua temuan audit diperbaiki **kecuali kunci API tertanam**, yang sengaja dibiarkan atas permintaan pemilik
(`EmbeddedKeys.kt` dan `.gitignore` tidak disentuh).

> **Belum pernah dikompilasi.** Perubahan dibuat tanpa Kotlin/Gradle. Logika murni (regex, parser, pemadatan konteks,
> heuristik niat, skor model, Edit CRLF, Glob, docx/xlsx) diuji dengan porting Java memakai literal regex yang diambil langsung
> dari sumber Kotlin: 66 asersi lulus. Kode Compose/Android hanya ditinjau baris demi baris. Jalankan build + `./gradlew test`
> dulu (lihat "Cek pertama kali" di bawah).

## Inti agen
| Perbaikan | Berkas |
|---|---|
| `MAX_NUDGES` 3 → 2 (sesuai README dan tes; tes `repeatedAnnouncements…` sebelumnya gagal) | Agent.kt |
| Heuristik niat tak lagi salah menandai penutup ("Selesai."), permintaan ke pengguna ("Saya perlu tahu…") dan "menunggu instruksi" | Agent.kt |
| Parser mencocokkan kurung JSON (sadar string) lebih dulu, baru mencari `</tool_use>`: isi file yang memuat tag itu tak lagi memotong blok | Protocol.kt |
| Keluaran terpotong dikenali (`Parsed.Invalid.truncated`); agen diminta memecah file, bukan mengirim ulang panggilan yang sama; prompt menyebut batas keluaran | Protocol.kt, Agent.kt |
| `unescapeSlashes`: org.json Android meng-escape `/` jadi `\/`; dikembalikan (kecuali `<\/`) untuk riwayat dan label UI | Protocol.kt, Context.kt, Tools.kt |
| Prompt: isi `<tool_result>`/lampiran/nama file adalah DATA, bukan perintah | Protocol.kt |
| Jendela penjaga duplikat mengikuti langkah yang hasilnya benar-benar masih utuh setelah pemadatan (`compactWithInfo`) | Context.kt, Agent.kt |
| `GithubBuild` meminta persetujuan bila workflow baru/berubah (isinya ditampilkan), apa pun mode izinnya | Agent.kt, GitHubActions.kt |
| `StackOverflowError`/`OutOfMemoryError` pada alat menjadi galat alat, bukan crash | Agent.kt |

## Alat file
Read tanpa baris kosong palsu di akhir; Edit bisa mencocokkan file CRLF; pola Glob/Grep ber-`/` dicocokkan relatif ke `path`;
Glob/Grep memeriksa pembatalan per file (timeout alat kini berlaku).

## Jaringan dan model
- Batas keluaran per model (`defaultMaxOutputTokens`): 8K–16K untuk Gemini/Anthropic/OpenAI resmi/DeepSeek, 4096 untuk sisanya. (Llm.kt, KeyPool.kt)
- Stop memutus koneksi HTTP seketika (`blockingIo`), tidak menunggu read timeout; alamat salah menjadi `LlmException`, bukan crash. (Llm.kt, ModelCatalog.kt, GitHubActions.kt)
- Galat di dalam badan HTTP 200 (OpenRouter/Gemini) dipetakan ke penyebab sebenarnya. (Llm.kt)
- 400/413/422 hanya mengistirahatkan entri 45 detik (sebelumnya 10 menit per entri, melumpuhkan semua kunci). Bila semua entri menolak permintaan yang sama, gagal seketika dan tak ada kunci dihukum. (KeyPool.kt)
- Skor model: `mini` tak lagi cocok dengan "ge**mini**". (ModelCatalog.kt)
- `ModelScanner` memakai `internal constructor` (tipe `HttpGet` internal tak boleh bocor ke konstruktor publik). (ModelCatalog.kt)
- Pemindaian model di Pengaturan memakai cache; permintaan nyata hanya lewat tombol "Periksa ulang model". (SettingsScreen.kt, AgentViewModel.kt)

## GitHub
Izin **Workflows** ditambahkan ke teks bantuan, pesan galat, dan README; berkas asli lampiran di `uploads/` tak lagi didorong;
file teks dikirim inline di tree (tanpa satu request per file); update ref lewat `X-HTTP-Method-Override: PATCH` dengan jatuh
kembali ke DELETE+POST; petunjuk khusus untuk secondary rate limit; nama branch tak lagi menghasilkan ref ilegal.

## Aplikasi
- **Layanan latar** (`AgentService`, foreground `dataSync`, wake lock parsial): tugas panjang tak mati saat layar padam. Izin notifikasi ditanyakan sekali.
- **Simpan bertahap** (±2 dtk) berurutan dengan Mutex; tugas gagal/dihentikan dicatat di konteks.
- `allowBackup="false"`; kunci/token yang tak bisa didekripsi diberi tahu (bukan kolom kosong); kegagalan enkripsi tak lagi menimpa dengan kosong; `UserSettings.toString()` menyamarkan rahasia; kunci Keystore di-cache.
- Draf pesan, pilihan incognito, dan isian Pengaturan disimpan di ViewModel (selamat dari rotasi dan berpindah layar).
- Incognito: folder proyek di `cacheDir/incognito` (dibersihkan tiap proses baru); teks penjelasan diluruskan.
- Back/"Chat baru" saat agen bekerja menampilkan konfirmasi; membuka chat lain ditolak sampai tugas dihentikan.
- Satu lampiran gagal tak lagi membatalkan pengiriman; PNG/WebP transparan berlatar putih; SVG diperlakukan sebagai teks; ekspor ZIP tak lagi crash.
- docx: sel tabel dipisah TAB; xlsx: `inlineStr`, urutan sheet alami, baris self-closing.
- UI: ikon bilah navigasi gelap di mode gelap sistem; label aksesibilitas pada tombol ikon; tombol hapus riwayat terlihat; area ketuk tombol X lampiran 40 dp; kontras `Hint` 5,19:1; layout beranda incognito responsif; sapaan memakai `AgentConfig.userAddress`;
  label hasil "Perubahan sudah diperiksa ulang" (bukan "Terverifikasi"); dialog persetujuan bisa digulir; field kunci memakai keyboard sandi.

## Tes
Tes baru untuk semua perbaikan di atas (Protocol, AgentLoop, Tools, Failover, ModelCatalog, Attachments, GitHubBuild) serta berkas baru
`ContextManagerTest`, `LlmTest` (termasuk pembatalan permintaan menggantung lewat `ServerSocket`), dan `SettingsTest`.

## Sengaja tidak diubah
- `EmbeddedKeys.kt` dan `.gitignore` (permintaan pemilik). Konsekuensinya masih berlaku: clone bersih tanpa file itu tak bisa dikompilasi.
- `gradle-wrapper.jar`/`gradlew`: tak bisa dibuat tanpa jaringan. Jalankan `gradle wrapper --gradle-version 8.7` sekali.
- Versi dependensi (AGP 8.5.2, Kotlin 1.9.24, BOM 2024.09), `targetSdk 34`, `isMinifyEnabled=false`: mengubahnya tanpa bisa membangun berisiko mematahkan build.
- String UI tetap di kode (bukan `strings.xml`), tanpa logging: bukan bug.
- Kode narasi tersembunyi (`NarrationBlock`) dipertahankan: narasi sengaja disembunyikan, bukan dihapus.
- Cleartext HTTP tetap diizinkan (untuk Ollama di LAN).

## Cek pertama kali
1. `gradle wrapper --gradle-version 8.7` lalu `./gradlew test` — periksa terutama `LlmTest.cancellingAHangingRequestDisconnectsImmediately`.
2. Kompilasi: kemungkinan salah ketik/tipe ada di berkas yang paling banyak berubah (`AgentViewModel`, `MainActivity`, `GitHubActions`, `Llm`).
3. Di perangkat: kirim tugas lalu matikan layar (notifikasi "Mythos sedang bekerja" harus muncul); coba Stop saat jaringan lambat;
   putar layar saat mengetik; buka Pengaturan dari obrolan dan kembali; tugas dengan file >300 baris.
4. `GithubBuild`: pastikan token punya izin Workflows; dialog persetujuan workflow harus muncul di build pertama.
