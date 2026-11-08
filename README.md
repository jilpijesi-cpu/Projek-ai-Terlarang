# Mythos

## Alur aplikasi
Beranda -> ketik tugas -> (jika belum diatur) layar Model & penyedia -> layar obrolan yang menjalankan agen.
- Ketuk label model di kolom input untuk membuka pengaturan (penyedia, API key terenkripsi Keystore). Model dipilih OTOMATIS:
  aplikasi mengambil daftar model dari penyedia (sesuai hak akses key), mengurutkan menurut perkiraan mutu, lalu mengetes SATU PER SATU dengan permintaan kecil.
  Model pertama yang merespons langsung dipakai dan pengujian berhenti (tidak menunggu model lain). Model yang kuota/kreditnya habis, error, atau tidak merespons
  dalam 10 detik (Ollama lokal: 60 detik) dilewati ke model berikutnya. OpenRouter dikecualikan: modelnya terkunci `openrouter/free`. Beberapa model berikutnya disiapkan sebagai cadangan TANPA diuji; model/kunci yang error atau sibuk saat tugas berjalan
  diganti sendiri ke cadangan itu, diam-diam di latar belakang (tanpa notifikasi di obrolan; hanya kegagalan total yang ditampilkan).
  Model reasoning yang menolak `temperature`/`max_tokens` (HTTP 400) disesuaikan otomatis (tanpa temperature, `max_completion_tokens`), saat uji maupun saat tugas berjalan; tidak ada tombol "Mode model reasoning" lagi. Hasil pemeriksaan di-cache 15 menit, jadi tugas berikutnya langsung jalan. Begitu kunci dimasukkan di Pengaturan, aplikasi langsung memindai dengan cara yang sama (terbaik dulu, berhenti di yang pertama merespons) dan hasilnya dipakai tugas berikutnya tanpa tes ulang.
  Nama model manual (opsional) hanya dicoba lebih dulu; cadangan otomatis tetap dipakai.
- Obrolan menampilkan alur streaming seperti rekaman aplikasi Claude: narasi singkat agen mengalir selagi model menulis, tiap kelompok langkah
  menjadi satu baris aktivitas yang bisa diketuk, dan jawaban akhir mengalir di tempat yang sama. Rencana tampil sebagai daftar tugas.
- Aksi berisiko memunculkan dialog Izinkan/Tolak (ringkasannya bisa digulir): `Delete`, dan `GithubBuild` saat workflow baru/berubah. AskUserQuestion memunculkan dialog pertanyaan.
- Hasil proyek ada di filesDir/projects/<nama>; menu di bilah atas mengekspornya sebagai ZIP.
- Menekan Kembali / "Chat baru" saat agen masih bekerja menampilkan konfirmasi (Hentikan & keluar / Tetap di sini); chat lain tak bisa dibuka sebelum tugas dihentikan.
- Draf pesan, pilihan incognito, dan isian Pengaturan yang belum disimpan disimpan di ViewModel: selamat dari rotasi layar dan dari berpindah ke Pengaturan.
- Dari beranda, tugas baru selalu membuat proyek baru; tugas lanjutan di layar obrolan memakai proyek yang sama.
- Build: HANYA lewat GitHub Actions (tidak ada shell lokal, Termux dihapus). Isi Pengaturan > Build: repo `pemilik/nama` + token fine-grained
  (Contents: Read and write, **Workflows: Read and write**, Actions: Read; token classic: scope `repo` + `workflow`). Repo harus punya minimal satu commit.
  Workflows wajib karena agen menulis file di `.github/workflows/`; tanpa izin itu GitHub menolak push-nya.
  Alat `GithubBuild` mem-push proyek sebagai satu commit ke branch `mythos/<proyek>` (branch utama tidak disentuh), menunggu run GitHub Actions
  untuk commit itu (trigger `push`), lalu mengembalikan hasil + potongan log job yang gagal. Run hijau berarti workflow yang ditulis agen lolos,
  bukan jaminan bahwa workflow itu benar-benar membangun/menguji aplikasinya.
  - Berkas asli lampiran di `uploads/` (gambar/PDF/dokumen/zip) TIDAK dikirim ke GitHub; folder hasil ekstrak zip tetap ikut.
  - File teks yang baru/berubah dikirim inline di dalam permintaan tree (tanpa satu permintaan per file) supaya tidak menabrak batas laju sekunder
    GitHub; hanya file biner yang diunggah sebagai blob. Branch diperbarui lewat POST + `X-HTTP-Method-Override: PATCH` (atomik), jatuh ke DELETE + POST bila ditolak.
  - Workflow dijalankan GitHub dengan secrets repo. Karena itu workflow yang baru/berubah SELALU meminta persetujuan pengguna (isinya ditampilkan)
    sebelum didorong, apa pun mode izinnya. Pakai repo khusus tanpa secrets dan token fine-grained yang dibatasi ke repo itu.
  Agen harus menulis sendiri `.github/workflows/*.yml` dengan `on: { push: { branches: ['mythos/**'] } }`; tanpa itu `GithubBuild` menolak.
  Tanpa token/repo, agen tetap menulis kode tetapi tidak bisa build/tes (ada pemberitahuan di obrolan).
- Riwayat: ikon sidebar di beranda membuka daftar obrolan/proyek (tersimpan di penyimpanan privat aplikasi; tidak ikut cadangan cloud karena `allowBackup=false`).
  Disimpan di awal, tiap ±2 detik selama agen bekerja, dan di akhir, sehingga progres tidak hilang bila proses dimatikan.
  Ketuk untuk melanjutkan; ikon sampah (atau tekan lama) menghapus riwayat saja atau beserta file proyek.
  Tugas yang gagal/dihentikan tetap dicatat di konteks sehingga "lanjutkan" pada giliran berikutnya tahu apa yang terjadi.
- Incognito: tidak masuk riwayat. Folder proyeknya di `cacheDir/incognito` dan dihapus tiap proses aplikasi baru. Pesan tetap dikirim ke penyedia model yang dipilih.
- Tugas berjalan di layanan latar (`AgentService`, foreground type `dataSync`, notifikasi "Mythos sedang bekerja" + wake lock parsial) agar tugas panjang tidak mati
  saat layar padam / aplikasi di latar belakang. Layanan hanya menjaga proses; agennya tetap berjalan di ViewModel. Izin notifikasi (Android 13+) ditanyakan sekali.
- Kunci: tiap penyedia memakai kunci utama dari Pengaturan, ditambah kunci tambahan (opsional, tersimpan terenkripsi Keystore).
  Tetap di satu kunci selama sehat; pindah saat 429/kuota habis/401-403/5xx/jaringan; kunci pulih dipakai lagi.
  Permintaan yang ditolak 400/413/422 hanya mengistirahatkan entri 45 detik; bila SEMUA entri menolak permintaan yang sama, tugas gagal seketika
  dan tidak ada kunci yang dihukum. Tidak ada kunci yang tertanam di APK.
- HTTP polos (cleartext) hanya diizinkan ke `localhost`, `127.0.0.1`, dan `10.0.2.2` (Ollama lokal/emulator) lewat `network_security_config.xml`;
  semua tujuan lain wajib HTTPS. Ollama di jaringan lokal: tambahkan IP-nya di berkas itu.
- Build release memakai R8 (`isMinifyEnabled`) dan `isShrinkResources`; aturan keep ada di `proguard-rules.pro`.

Layar beranda Mythos (Jetpack Compose) + inti agen AI (paket `com.mythos.app.agent`).
Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7, minSdk 26.

## Build
- Android Studio: File > Open > pilih folder ini > tunggu sync > Run.
- Terminal (JDK 17 + Android SDK, ANDROID_HOME terisi):
    ./gradlew assembleDebug               # APK: app/build/outputs/apk/debug/app-debug.apk
    ./gradlew test                        # uji unit logika agen

## CI untuk Mythos sendiri
`.github/workflows/ci.yml` (bukan untuk proyek yang dibuat agen): pada push ke `main`/`master`, pull request, dan manual menjalankan
`gradle :app:testDebugUnitTest`, `:app:lintDebug` (belum memblokir), `:app:assembleDebug`, dan `:app:assembleRelease` (cek R8), lalu mengunggah laporan tes, laporan lint,
dan APK debug sebagai artefak.
- Gradle dipasang oleh `gradle/actions/setup-gradle` dan dipanggil sebagai `gradle` (repo juga memuat `gradlew`, tetapi CI tidak memakainya).
- Cabang `mythos/**` sengaja tidak memicu CI ini, supaya alat `GithubBuild` aplikasi tidak ikut memicunya bila repo yang sama dipakai sebagai target build.

## Pengaduan dan sengketa (v70)
Alat `ComposeEmail` (membuka draf email berlampiran PDF; tidak pernah mengirim sendiri, selalu minta persetujuan) dan `SetReminder` (alarm/kalender untuk tenggat),
berkas memori `KASUS.md` (dimuat otomatis), dan aturan perilaku pengaduan di prompt. Rincian: `docs/changelog/CHANGELOG-v70-pengaduan.md`.

## Titik pulih per tugas
Setiap tugas agen dicatat agar perubahannya bisa dilihat dan dibatalkan:
- Tepat SEBELUM `Write`/`Edit`/`Delete` mengubah sebuah file untuk pertama kalinya dalam satu tugas, isi aslinya disalin (copy-on-write; seluruh proyek
  tidak disalin). Saat tugas selesai (atau dihentikan/gagal di tengah jalan) isi akhirnya disalin juga dan kartu "N file diubah  +A −R" muncul di obrolan.
- Ketuk kartu untuk daftar file (A = baru, M = diubah, D = dihapus), ketuk file untuk diff-nya (konteks 3 baris, maks. 400 baris).
- "Batalkan tugas ini" mengembalikan setiap file ke keadaan sebelum tugas dan merapikan folder yang dulu dibuat agen. File yang SUDAH diubah lagi
  sesudah tugas itu (isinya beda dari isi akhir tercatat) DILEWATI, jadi pekerjaan sesudahnya tidak hilang. Pembatalan hanya bisa sekali; agen diberi tahu
  lewat catatan riwayat supaya membaca ulang file sebelum mengubahnya. Tombol nonaktif selama agen bekerja.
- Disimpan di `filesDir/checkpoints/<proyek>/<id>/` (di luar folder proyek: tak terlihat agen, tak ikut ZIP ekspor, tak didorong ke GitHub), maksimum 15 per
  proyek (yang tertua dihapus). Dihapus bersama riwayat obrolannya. Chat incognito: `cacheDir/incognito-checkpoints`, dibersihkan tiap proses baru.
- Batas: file > 10 MB tidak dicadangkan (perubahannya tak bisa dibatalkan; ditandai di laporan), file teks > 2 MB tidak dihitung barisnya, dan file biner hanya
  ditandai "biner" tanpa diff. Diff memakai LCS dengan pemangkasan awalan/akhiran; pasangan file yang terlalu besar (> 4 juta sel) ditampilkan sebagai "ganti semua".
- Hanya alat Write/Edit/Delete yang mengubah file proyek, jadi jurnal lengkap. Perubahan oleh `GithubBuild` tidak ada (ia hanya membaca).

## Memakai agen
```kotlin
val agent = MythosAgent.create(
    provider = Presets.anthropic(apiKey, "<nama-model>"),   // atau openAi / deepSeek / groq / ollama / gemini
    projectDir = File(filesDir, "projects/todo-app"),
    approver = Approver { tool, summary -> /* tampilkan dialog Setuju/Tolak */ true },
    builder = GitHubBuildService(GitHubConfig(token, "pemilik", "repo")),   // opsional; null = tanpa build
    ask = { question -> /* tampilkan pertanyaan, kembalikan jawaban pengguna */ "" },
)
lifecycleScope.launch {
    agent.events("Buat aplikasi catatan sederhana ...").collect { e ->
        when (e) {
            is AgentEvent.Thought -> {}      // alasan singkat tiap langkah
            is AgentEvent.PlanChanged -> {}  // rencana + status
            is AgentEvent.ToolStart, is AgentEvent.ToolEnd -> {}
            is AgentEvent.Notice -> {}       // format diperbaiki, duplikat diblokir, konteks dipadatkan
            is AgentEvent.Final -> {}        // ringkasan + flag verified
            is AgentEvent.Failed -> {}
        }
    }
}
```
Model OpenAI terbaru: set `temperature = null` dan `tokenParam = "max_completion_tokens"` di `ProviderConfig`.

## Logika agen (meniru siklus kerja Claude Code)
Siklus: **kumpulkan konteks -> bertindak dengan alat -> lihat hasil -> ulangi**. Model menulis teks bebas dan boleh memanggil beberapa alat
sekaligus dalam satu balasan; giliran berakhir ketika model membalas **tanpa** pemanggilan alat (teks itu adalah jawaban akhir). Tidak ada lagi
JSON `thought/final`, klasifikasi niat, penjaga sapaan, atau gerbang yang menolak jawaban akhir.
1. Transport tetap teks polos supaya pergantian model/kunci di tengah tugas mulus. Pemanggilan alat:
   `<tool_use>{"name": "Read", "input": {"file_path": "a.kt"}}</tool_use>`; hasil kembali sebagai `<tool_result id= name= is_error=>`.
   Pengingat dari aplikasi memakai `<system-reminder>`.
2. Alat (nama & parameter mengikuti Claude Code): `Read` (offset/limit, keluaran `nomor<TAB>teks`), `Edit` (old_string/new_string/replace_all),
   `Write`, `Glob`, `Grep` (output_mode content/files_with_matches/count, -i, -n, -A/-B/-C, glob, head_limit), `LS`, `TodoWrite`, `AskUserQuestion`,
   `Delete`, `GithubBuild` (hanya bila GitHub diatur), `ExitPlanMode` (hanya mode PLAN). Tidak ada Bash karena tidak ada shell di ponsel.
3. Baca-sebelum-ubah: `Edit`/`Write` pada file yang ada ditolak bila belum di-`Read` atau file berubah sejak dibaca (pesan galat seperti Claude Code).
4. Mode izin (`AgentConfig.permissionMode`): `DEFAULT` (Write/Edit/GithubBuild/Delete minta izin), `ACCEPT_EDITS` (bawaan; Delete dan GithubBuild dengan workflow
   baru/berubah minta izin), `PLAN` (hanya alat baca, rencana diajukan lewat `ExitPlanMode` dan butuh persetujuan; tersedia lewat API, belum ada saklar di UI).
5. Memori proyek: `CLAUDE.md` (atau `MYTHOS.md`) di akar proyek dimuat ke pesan pertama, bersama lingkungan dan pohon proyek.
6. Gaya jawaban: ringkas, langsung, tanpa pembuka/penutup, mengikuti bahasa pengguna. Sapaan bawaan "BOS" (`AgentConfig.userAddress`); isi `null` untuk mematikannya.
7. Pengaman yang dipertahankan (tidak ada di Claude Code, perlu di ponsel): aksi identik di kondisi tak berubah diblokir; 2 gagal = petunjuk,
   3 gagal = refleksi, 7 gagal = berhenti; batas giliran; hasil alat lama dipadatkan otomatis; pengingat `TodoWrite` bila lama tak diperbarui.
8. Balasan pendek yang hanya berisi niat ("Saya akan mencari ...") tanpa alat tidak dianggap jawaban akhir: agen diminta benar-benar bertindak (maks. 2 kali per tugas).
   Penutup ("Selesai."), permintaan ke pengguna ("Saya perlu tahu ...", "tolong kirim ...") dan "menunggu instruksi" dikecualikan dari deteksi ini.
9. Lampiran zip: isi hasil ekstraksi (3 tingkat teratas) disertakan di catatan lampiran agar agen langsung tahu file apa yang ada.
10. `verified` pada hasil akhir hanya informasi: true bila tidak ada file berubah yang belum dibaca ulang / belum lolos build. UI menampilkannya sebagai
    "Perubahan sudah diperiksa ulang", BUKAN jaminan kode berjalan.
11. Batas keluaran: tiap balasan dibatasi (4096 token; dinaikkan ke 8K–16K untuk keluarga model yang batasnya diketahui lega: Gemini, Anthropic, OpenAI resmi, DeepSeek).
    Balasan yang terpotong (JSON panggilan alat tak seimbang) tidak dikirim ulang apa adanya: agen diminta memecah file (Write bagian pertama, lanjutkan dengan Edit,
    atau bagi ke beberapa file kecil). Prompt sistem juga menyuruh ≤ ~150 baris per Write.
12. Keamanan masukan: isi `<tool_result>` (isi file, teks lampiran, nama file) diperlakukan sebagai DATA, bukan perintah. `<system-reminder>` hanya dipercaya di luar `<tool_result>`.
13. Pembatalan: tombol Stop memutus koneksi HTTP seketika (tidak menunggu read timeout); Glob/Grep memeriksa pembatalan per file, sehingga timeout alat benar-benar berlaku.
    `StackOverflowError` (regex rumit) dan `OutOfMemoryError` pada alat menjadi galat alat, bukan crash.

## Lampiran (file, dokumen, gambar, kamera)
Tombol "+" > Tambahkan ke chat: **Kamera**, **Foto**, **File**. Lampiran tampil sebagai kartu di atas kolom input (X untuk batal), maks. 6 per pesan, 25 MB per file.
- Disalin ke `uploads/` di proyek. Gambar diperkecil (maks. 1600 px), diputar sesuai EXIF, PNG/WebP transparan diberi latar putih (JPEG tak punya alfa), dikirim ke model sebagai gambar (Claude, OpenAI-kompatibel, Gemini).
- Teks/kode (termasuk `.svg`): dibaca agen dengan `Read`. `.docx/.pptx/.xlsx`: teks diekstrak ke `<nama>.txt` (sel tabel dipisah TAB, sheet berurutan alami, `inlineStr` didukung). `.zip`: diekstrak aman (anti zip-slip) ke `uploads/<nama>/`.
- Satu lampiran yang gagal diproses dilewati dengan catatan di obrolan; lampiran lain dan pesan tetap terkirim.
- PDF: 6 halaman pertama dirender menjadi gambar (teks tidak diekstrak); butuh model yang bisa melihat gambar.
- Model yang bisa melihat gambar (dikenali dari nama) dicoba lebih dulu secara otomatis; model tanpa kemampuan itu tidak diberi gambar dan agen diberi catatan.
- Gambar hanya dikirim pada 6 panggilan pertama agar hemat token; agen diminta mencatat isi pentingnya.

## Tampilan streaming agen (v31)

Alurnya meniru rekaman aplikasi Claude dan sekarang **streaming sungguhan**, bukan animasi setelah balasan selesai.

**Dari model ke layar**
1. `LlmClient.stream()` membaca Server-Sent Events (OpenAI-compatible, Anthropic, Gemini) dan melaporkan teks *kumulatif* (paling sering tiap 40 ms).
   Server yang membalas JSON biasa tetap jalan; klien yang hanya punya `complete()` jatuh ke satu potongan di akhir.
   Coba ulang dan pergantian kunci mengosongkan teks parsial (`onText("")`).
2. `Agent` meneruskan teks itu sebagai `AgentEvent.Streaming(text, tool, target)`: `text` = narasi tanpa blok `<tool_use>`; `tool`/`target` terisi begitu
   model mulai menulis pemanggilan alat (label "Menulis X.kt" muncul selagi JSON-nya masih mengalir). Pendengar jaringan menulis ke
   `Channel.CONFLATED`, jadi jaringan tidak pernah menunggu UI.
3. `TimelineReducer` (`Timeline.kt`, murni Kotlin) membuat satu `ThoughtItem(live = true)` dan memperbaruinya di tempat. Saat balasan selesai, item yang
   sama menjadi narasi (ada alat) atau `FinalItem` (jawaban akhir) dengan id tetap, sehingga tidak berkedip dan animasinya tidak mengulang.

**Di layar (`ChatScreen`)**
- Urutan blok: pesan pengguna → narasi → baris aktivitas → narasi → … → jawaban akhir. Tanpa antrean tampilan: semuanya tampil saat terjadi.
- Narasi dan jawaban memakai serif (jarak baris 22 dp, margin 20 dp); 9 karakter terakhir memudar selama mengalir dan dikejar mulus
  (`rememberRevealed`), jadi tampilan hanya tertinggal beberapa karakter dari aliran asli.
- Baris aktivitas: aktif = tiga titik oranye + label berkilau yang berganti di tempat (ditahan ≥ 500 ms); selesai = titik berubah (pudar-silang)
  menjadi ikon jam (ikon terminal untuk kelompok yang menjalankan build) dan label menjadi judul kelompok (satu alat → judulnya, beberapa → ringkasan).
  Saat teks sedang mengalir tidak ada baris di bawahnya; baris baru muncul setelah teks selesai.
- Label aktif: alat yang sedang ditulis model → alat yang sedang jalan → "Proses berpikir".
- "Masih diproses…" muncul bila ±10 detik tidak ada perubahan yang terlihat (`progressKey`) dan hilang begitu ada yang baru.
- Tombol bulat "ke bawah" muncul saat pengguna menggeser menjauh dari ujung percakapan. Titik dan kilau memakai jam bingkai absolut (`rememberPhase`),
  jadi tidak melompat ketika baris berpindah komposable.

**Tidak ditiru (tidak ada padanannya di Mythos):** kartu file ZIP (alat `present_files` milik Claude) dan judul ringkasan "berpikir" dari model.
Ringkasan perubahan file tetap tampil sebagai kartu `ChangesCard` di akhir giliran.
