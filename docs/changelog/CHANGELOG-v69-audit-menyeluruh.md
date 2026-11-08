# Changelog v69 — Audit Menyeluruh & Perbaikan Semua Bug Tanpa Terkecuali

Rilis **v69 (`1.0.69`, `versionCode = 69`)** merupakan hasil audit menyeluruh terhadap seluruh kode sumber MythosV6 (`agent`, `ui`, dan root/build/config) dan memperbaiki seluruh bug serta celah error yang ditemukan:

## 1. Mesin Agen, Protokol, Konteks, Alat & Jaringan (`com.mythos.app.agent`)
- **`Agent.kt`**:
  - Menghapus gerbang persetujuan `newHost` warisan pada `WebFetch` yang sebelumnya membuat `WebFetch` (padahal `Risk.SAFE`) memunculkan dialog persetujuan di agen utama dan **selalu ditolak** di dalam `SubAgent` (karena `SubAgent` memakai `Approver { _, _ -> false }`).
  - Memastikan lampiran gambar dari `ViewImages` tetap menempel pada `view.lastIndex` meskipun jumlah pemanggilan `ViewImages` dalam satu sesi sudah melewati `MAX_IMAGE_CALLS` (8), sehingga pemanggilan ke-9 dan seterusnya tidak mengirim hasil kosong tanpa gambar ke model vision.
  - Menggunakan ukuran konteks aktual yang dikirim (`sendView.sumOf { it.text.length }`) saat menghitung `ContextManager.shrunkBudget(...)` pada pemulihan error HTTP 413, bukan `history.sumOf` yang belum dipadatkan.
  - Mencegah pemanggilan `Write`/`Edit` yang ditolak karena belum `Read` (`NOT_READ`) masuk ke daftar `st.seen` (detektor duplikat), sehingga setelah model melakukan `Read` dan mengulangi `Write`/`Edit` dengan argumen yang sama, panggilan tersebut tidak lagi diblokir sebagai duplikat.
  - Membuat pengecekan `isParallelTask` tidak peka huruf besar/kecil (`equals("Task", ignoreCase = true)`).
  - Mereset penghitung `st.persist = 0` saat `GithubBuild` berhasil agar percobaan berikutnya tidak mewarisi hitungan gagal lama.
- **`Context.kt`**:
  - Memperbaiki bug di `ContextManager.squeeze` yang terus memotong ulang pesan terbesar yang sama hingga 50 kali saat panjangnya sudah mencapai batas bawah 400 karakter (`"[dipotong ...]" [dipotong ...]`), dengan melacak indeks pesan yang sudah dipotong (`touched`) dan memastikan pemotongan benar-benar mengurangi panjang teks.
- **`Protocol.kt`**:
  - Memperbaiki regex `PARTIAL_TAG_TAIL` (`Regex("<[A-Za-z0-9_|/]*$")`) agar ekor tag parsial seperti `</tool_u` atau `<|start_header_` di akhir streaming dipotong dengan bersih.
  - Memperbaiki `insideCode` agar memeriksa seluruh rentang kode sebaris (inline backticks) pada baris yang sama, bukan hanya sebelum backtick pertama, sehingga tag seperti `<tool_use>` di luar backtick kedua pada baris yang sama tidak gagal terdeteksi.
  - Memperbaiki `garbleCut` dan `isGarbled` sehingga `cutMarkup(text, final = true)` memotong bagian akhir yang rusak (garbled) dan tidak terhalang kondisi mati `garbleCut >= 0` setelah `cutAt`.
  - Memperbaiki batas pencarian `<tool_use>` berikutnya (`nextOpen in 0 until e`) agar blok `<tool_use>` pertama yang tidak ditutup tidak menelan blok `<tool_use>` kedua yang valid.
  - Menambahkan penjaga `firstOpen(raw) >= 0` dan `!insideCode(raw, m.range.first)` pada `normalizeFunctionTags` dan `normalizeNative` agar contoh kode di dalam blok markdown tidak diubah menjadi panggilan alat.
  - Memperbaiki `fnValue` agar parameter bertipe string (`content`, `old_string`, `new_string`, `prompt`, `pattern`, `query`, `plan`, dll.) selalu dipertahankan sebagai `String` meskipun berisi `"true"`, `"123"`, atau `"{\"a\":1}"`, serta melengkapi `FN_NUMERIC` (`from_line`, `to_line`, `start_line`, `end_line`).
  - Menambahkan dukungan `<invoke` pada `pendingTool` dan menyempurnakan `looseCalls` (`extractLooseInput` mendukung `input`, `args`, `arguments`, `parameters`, maupun field tingkat atas, serta mengembalikan `status` sebagai pikiran asisten).
- **`KeyPool.kt`**:
  - Mencocokkan pasangan `(apiKey, model)` di `adoptState` agar kunci API yang sama dengan beberapa model di OpenRouter/Groq tidak saling menimpa status `exhaustedAt`/`cooldownUntil`.
  - Melindungi `callsOnCurrent++` di `noteSuccess` dengan `if (e === current)` untuk mencegah kondisi balapan paralel (`Task` sub-agen) memicu rotasi kunci prematur.
  - Menambahkan `@Synchronized fun activeEntryCount()`, menandai `spec` dan `lastUsed` sebagai `@Volatile`, serta mempertahankan kode HTTP pada pesan error saat seluruh kunci di `FailoverClient` habis agar pemulihan HTTP 413 di `Agent.kt` tetap berjalan.
- **`Llm.kt`**:
  - Mengganti `.optString(...)` dengan helper `.str(...)` pada field JSON yang bisa bernilai `null` (`openAiWhole`, `anthropicDelta`, `geminiDelta`, `anthropicWhole`, `geminiWhole`, `errorException`) agar `JSONObject.NULL` di Android tidak berubah menjadi string `"null"`.
  - Memperbaiki klasifikasi kode HTTP di `errorException` (`404`/`400` sebagai permanen, `503` sebagai transien) agar error permanen tidak diulang 4 kali dengan jeda sia-sia.
  - Memperketat `GEMINI_BIG_OUTPUT` dan mengecualikan keluarga Gemma khusus teks (`gemma-2`, `gemma-3-1b`, `gemma-3n`) dari `supportsVision`.
- **`ModelCatalog.kt`**:
  - Menyeragamkan penggunaan `probeTimeoutFor(base)` di `scan()` dan `resolveSpec()`.
- **`Tools.kt`**:
  - Menormalisasi `\` menjadi `/` dan memperbaiki pengecekan batas direktori di `ProjectSandbox.resolve`.
  - Memperbaiki penggantian CRLF di `Edit` ketika `old_string` hanya satu baris tetapi `new_string` multi-baris.
  - Menangani kurung kurawal `{` yang tidak ditutup pada `globToRegex` dan membungkus `f.readLines()` dengan `try/catch` di `Grep`.
- **`GitHubActions.kt`, `Web.kt`, `PdfPages.kt`, `ViewImages.kt`, `ImagesToPdf.kt`, `AttachmentText.kt`**:
  - Menambahkan `"gradlew"` dan `"gradlew.bat"` ke `EXECUTED_NAMES` serta mengurutkan file sebelum `.take(40)`.
  - Mengubah `decodeEntities` di `Web.kt` dan unescape XML di `AttachmentText.kt` menjadi satu lintasan (single-pass) agar entitas beruntun seperti `&#38;lt;` tidak ter-unescape ganda dan mengecualikan surrogate UTF-16.
  - Menambahkan blokir awalan NAT64 lokal `64:ff9b:1::/48` di `isPrivateAddress` (`Web.kt`).
  - Membatasi tinggi maksimum bitmap di `PdfPages.render` (`RENDER_WIDTH_PX * 3`), mencadangkan slot untuk sisa file gambar di `ViewImages`, meratakan kanal alpha ke latar putih sebelum kompresi JPEG, serta menghapus hash lama dari `env.session.known` saat `ImagesToPdf` menimpa PDF yang sudah ada.

## 2. Antarmuka Pengguna, Siklus Hidup & Penyimpanan (`com.mythos.app.ui` & `com.mythos.app`)
- **`Settings.kt`**:
  - Memisahkan `SecretBox.existingKey()` (dipakai `decrypt`) dari `getOrCreateKey()` (dipakai `encrypt`) sehingga kegagalan baca sementara pada Android Keystore tidak pernah membuat kunci baru yang menghancurkan seluruh API key tersimpan, serta menghapus jebakan `keepUnreadable` permanen saat pengguna menyimpan kunci baru secara eksplisit.
  - Menyertakan `permissionMode` pada `UserSettings.toString()`.
- **`AgentService.kt`**:
  - Membatalkan `Runnable` penghentian tertunda (`main.removeCallbacks`) saat `start(ctx)` dipanggil kembali dalam jendela 800 ms, mengurangi `runs` jika `startForegroundService` melempar eksepsi, dan mereset `runs` pada `onTimeout`.
- **`AgentViewModel.kt` & `MainActivity.kt`**:
  - Memperbaiki kehilangan penyimpanan akhir obrolan di `persist()` saat pengguna berpindah obrolan ketika agen sedang menyelesaikan giliran (menggunakan pelacakan `deletedIds` alih-alih `projectDir?.name == dir.name`).
  - Membatasi penggantian blok `Plan` secara in-place hanya pada giliran saat ini (`idx > lastUser`).
  - Mempertahankan draf halaman awal (`homeDraft`) ketika pengguna diarahkan ke layar Pengaturan karena API key belum diisi.
  - Menyimpan path foto kamera tertunda (`pending_camera_file`) ke `SharedPreferences` agar tahan terhadap penghentian proses oleh OS saat aplikasi kamera terbuka.
  - Memperbaiki keamanan thread dan pembersihan file sementara pada `attachFromUri`/`attachFromUris`, mencegah benturan nama direktori proyek di detik yang sama (`newProjectDir`), menetapkan `locked = true` untuk penyedia model terkunci di `buildKeyPool`, serta menyambungkan `newChat()`, `pickProject()`, dan `setMainKey()`.
- **`Attachments.kt`**:
  - Memperbaiki urutan matriks rotasi + cermin untuk EXIF `ORIENTATION_TRANSPOSE` dan `ORIENTATION_TRANSVERSE`, menambahkan `heic`/`heif`/`bmp` ke `guessMime`, menggunakan `uniqueFile` untuk hasil ekstraksi teks dokumen Office, dan menghapus file sementara hanya setelah penyalinan berhasil.
- **`ChatScreen.kt`, `SettingsScreen.kt`, `CleanText.kt`, `CopyBlocks.kt`, `Design.kt`, `Timeline.kt`, `Workspace.kt`, `IntroSplash.kt`, `Sheets.kt`**:
  - Memperbaiki `RevealClock.clear` yang sebelumnya menghapus blok yang baru saja dibuka alih-alih blok sebelumnya (`prev`).
  - Menambahkan fallback ketika blok teks berada di luar layar pada `LaunchedEffect(lastTextId, running)` agar animasi ketik tidak macet selamanya.
  - Mencegah `StepsView` merekam `thinkingPhrase` yang berputar ke dalam `lastLive[0]` sebagai langkah alat palsu, dan memperbaiki pencarian indeks scroll menggunakan daftar `blocks` yang sedang dirender.
  - Menyinkronkan `LocalTextInput` di `SettingsScreen.kt` saat nilai luar berubah meskipun fokus masih aktif, memfilter `KeyEntry` kosong, menyembunyikan teks mentah untuk seluruh kunci yang memakai `secret = true`, dan menambahkan `Modifier.imePadding()`.
  - Menyempurnakan pembersihan kebocoran protokol di `CleanText.kt`, deteksi pagar kode bertingkat (```` ```` ````) di `CopyBlocks.kt`, ketahanan pembagi tabel di `Design.kt`, pelolosan karakter pipa `|` pada `Timeline.kt`, penutupan `ZipFile` dengan `.use` di `Workspace.kt`, penghapusan nilai mati di `IntroSplash.kt`, serta perbaikan urutan modifier di `Sheets.kt`.

## 3. Build, ProGuard, Tema & Skrip
- **`app/build.gradle.kts`**: Menaikkan versi ke `versionCode = 69` dan `versionName = "1.0.69"`.
- **`app/proguard-rules.pro`**: Merapikan komentar dan aturan keep untuk kelas galat (`Throwable`) serta konstanta enum yang disimpan lewat `name()`. Tidak ada aturan keep untuk serialisasi karena JSON ditulis manual lewat `org.json`.
- **`app/src/main/res/values-v35/themes.xml`**: Menyesuaikan atribut tema Android 15 (API 35) agar konsisten dengan tema utama.
- **`scripts/pin-actions.sh`**: Menambahkan `set -euo pipefail`, pengecekan dependensi, dan penanganan kuota API GitHub.

## Catatan penggabungan (v69-merge)
- Dasar: v69 hasil audit. `ChatScreen.kt` dikembalikan ke versi v68 agar perbaikan anti-kedipan v65 (`LIVE_SLOT_KEY`, pelari gulir per bingkai, `StallNote` tinggi-nol) tidak hilang.
- Dua perbaikan audit dipertahankan di `ChatScreen.kt`: `RevealClock.clear` kini menghapus blok sebelumnya (`prev`), dan `StepsView` tidak merekam frasa berpikir yang berputar sebagai langkah alat (`isWaiting`).
- Belum dikompilasi dan belum dijalankan di perangkat.

## Pembersihan pasca-gabungan
- Menghapus `webHostOf`, `isTrustedDocHost`, dan `TRUSTED_DOC_HOSTS` di `Web.kt` beserta `WebHostApprovalTest.kt`: sisa gerbang persetujuan host `WebFetch` yang sudah tidak dipakai (bertentangan dengan keputusan v62 bahwa `WebFetch` berstatus `Risk.SAFE`).
- Menghapus 14 impor yang tidak terpakai (`MainActivity`, `Workspace`, `IntroSplash`, `Sheets`, `HomeScreen`, `Design`, `Tools`).
- Pemeriksaan statis: tidak ada impor ganda, tidak ada deklarasi tingkat-atas ganda dalam satu paket, tidak ada rujukan tersisa ke kode yang dihapus.
- Belum dikompilasi: jalankan `./gradlew testDebugUnitTest assembleDebug`.

## Layar mengikuti teks (permintaan pengguna)
- `ChatScreen.kt`: pesan pengguna tetap disematkan di atas, tetapi begitu jawaban memenuhi layar (`rawTail` kembali 0), layar otomatis mengikuti teks yang mengalir (`stick = true`, `pinned = null`).
- Pengguna tetap bebas menggeser: sentuhan pertama melepas pin dan mematikan pengikutan; mengikuti kembali bila pengguna menggulir ke dasar atau menekan tombol turun.
- Belum dikompilasi dan belum diuji di perangkat.

## Tes tambahan
- `androidTest/SecretBoxInstrumentedTest`: enkripsi/dekripsi kunci API di Keystore sungguhan (6 tes: roundtrip, unicode/kosong, 16 kunci, IV acak, masukan rusak, ciphertext diubah). Tidak menyentuh SharedPreferences aplikasi. Dijalankan di emulator/perangkat: `./gradlew :app:connectedDebugAndroidTest`.
- `test/ShouldStartFollowingTest`: 6 tes untuk aturan kapan layar mulai mengikuti teks (fungsi murni `shouldStartFollowing` di `ChatScreen.kt`).
- `build.gradle.kts`: `testInstrumentationRunner` dan dependensi `androidTest`. CI kini mengompilasi (tidak menjalankan) tes instrumented.
- Lint belum dijadikan memblokir: belum pernah dijalankan pada kode ini. Jalankan `./gradlew :app:lintDebug`, bersihkan temuannya, baru hapus `continue-on-error`.

## Alat baru: MakePdf (PDF dari teks dan gambar)
- Masalah: agen hanya punya `ImagesToPdf` (gambar saja), sehingga surat, pengaduan, dan berkas bukti dengan ringkasan hanya bisa berupa teks di layar.
- `agent/PdfLayout.kt` (logika murni, tanpa kelas Android): membaca spesifikasi JSON, membungkus teks, dan menata blok ke halaman A4. Judul bagian dijaga satu halaman dengan paragraf dan gambar yang mengikutinya; gambar tidak dipotong dan tidak diperbesar melebihi ukuran asli.
- `agent/MakePdf.kt`: merender ke `PdfDocument` bawaan Android. Footer opsional dan nomor halaman "Hal. n/N" ditambahkan otomatis. Gambar diperkecil ke 144 dpi supaya PDF tidak membengkak. Hasil alat mencantumkan halaman tiap gambar agar agen bisa memeriksa daftar lampiran.
- Jenis blok: `title`, `subtitle`, `heading`, `text`, `numbered`, `bullets`, `fields`, `image`, `rule`, `pagebreak`. Batas: 300 blok, 40 gambar, 60 halaman, 150.000 karakter. Emoji dan penanda tebal markdown dibuang.
- Terdaftar di `ToolRegistry`, `WRITE_TOOLS`, label timeline, dan `LOOSE_NAMES`; `Protocol.kt` mendapat dua butir petunjuk (tata letak, tidak mengarang fakta, cek nomor halaman).
- Tes: `PdfLayoutTest` (21 tes JVM untuk pembungkus teks, alur halaman, gambar, daftar, kolom, dan penguraian).
- Batasan: hanya huruf yang ada di font sans-serif bawaan; tidak ada teks tebal di tengah kalimat, tabel bergaris, atau tanda tangan gambar selain lewat blok `image`. Belum dikompilasi dan belum diuji di perangkat.
