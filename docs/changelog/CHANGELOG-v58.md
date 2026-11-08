# v58 — Tampilan UI 1:1 sesuai video referensi (mempertahankan logo tengah & "Halo, BOS")

Seluruh tampilan antarmuka (`Design.kt`, `HomeScreen.kt`, `Sheets.kt`, `HistoryDrawer.kt`, `ChatScreen.kt`, `MainActivity.kt`, `AutoStatus.kt`) diperbarui agar **1:1 identik dengan video referensi** (rekaman 1170×2532 px), dengan tetap mempertahankan **logo maskot piksel di tengah (`PixelMascot`)** dan **teks sapaan `"Halo, BOS"`** sesuai permintaan:

1. **Layar Beranda (`HomeScreen.kt`, `Design.kt`)**:
   - Latar belakang utama `#F8F8F6` (termasuk area bilah navigasi bawah yang menyatu).
   - **Bilah Atas (`TopBar`)**: tombol menu di kiri atas dan tombol mode samaran di kanan atas kini berada di dalam lingkaran 44 dp (`CircleIconButton`) berlatar `#F8F8F6`, tepi tipis `0,67 dp` `#D4D4D2`, dan bayangan halus `2 dp`. Ikon menu (`MenuGlyph`) disesuaikan menjadi dua garis atas sama panjang (`18 dp`) dan garis bawah setengah panjang (`9 dp`).
   - **Bagian Tengah**: mempertahankan `PixelMascot` dan teks `"Halo, BOS"`, dilengkapi pil **`"Paket Free · Upgrade"`** (`36 dp`, latar `#EEECEB`) tepat di bawahnya.
   - **Kartu Input (`MythosInputCard`)**: sudut membulat `26 dp`, tepi solid `0,67 dp` `#DADAD8`, placeholder `"Chat Mythos..."`, tombol bulat `+`, pil model **`"Sonnet 5.5 Sedang"`** (`uiModelChip`), tombol mikrofon, serta tombol kirim bulat oranye (`#BF5837`) yang selalu tampil di sebelah kanan tombol mikrofon.

2. **Lembar Bawah "Tambahkan ke chat" (`Sheets.kt`, `Design.kt`)**:
   - Pegangan atas `57×4 dp` (`#B7B7B5`), tombol tutup `X` di dalam lingkaran `44 dp` berbayangan halus, judul `"Tambahkan ke chat"` tebal menengah.
   - Tiga petak aksi (`Kamera`, `Foto`, `File`) berlatar `#EEECEB` dengan sudut `22 dp` dan ikon garis luar kustom (`CameraGlyph`, `PhotoGlyph`, `FileUploadGlyph`) tanpa lingkaran latar di belakang ikon.
   - Tiga baris kartu putih di bawahnya: **`"Tampilkan foto terbaru"`** berwarna biru `#1B5091` (`RecentPhotosGlyph`), **`"Tambahkan ke proyek"`** (`ProyekGlyph` + `"Tidak ada"` + `>`), dan **`"Konektor"`** (`ConnectorGlyph` + `>`).

3. **Lembar Bawah "Pemilihan model" (`Sheets.kt`, `AutoStatus.kt`)**:
   - Judul `"Pemilihan model"`, kartu putih bersudut `24 dp` berisi 4 tingkat model sesuai video:
     - **Fable 5.1** dengan lencana biru `Pro, Max, atau Ultra` (`"Untuk tantangan terberat Anda"`)
     - **Opus 5.5** dengan lencana biru `Pro` (`"Untuk pekerjaan kompleks dan tugas sehari-hari"`)
     - **Sonnet 5.5** dengan tanda centang biru `#1B5091` (`"Paling efisien untuk tugas yang lebih sederhana"`)
     - **Haiku 5.5** (`"Tercepat untuk jawaban cepat"`)
   - Catatan kaki di bawah kartu beserta tautan bergaris bawah `"Pelajari selengkapnya"` (membuka pengaturan model lengkap).

4. **Mode Samaran / Incognito (`HomeScreen.kt`, `Design.kt`)**:
   - Judul tengah bilah atas **`"Chat samaran"`**.
   - Tombol kanan atas (`IncognitoToggleButton`) menampilkan lingkaran luar terang dengan lingkaran dalam gelap `#2F2F2B` berisi ikon hantu garis putih.
   - Bagian tengah menampilkan ikon hantu hitam pekat (`FilledGhost`) dan teks penjelasan `"Chat incognito tidak masuk ke riwayat, memori, dan pencarian Anda, serta tidak digunakan untuk melatih Claude. Pelajari selengkapnya"`.
   - Kartu input bawah tetap menggunakan garis tepi utuh (bukan garis putus-putus).

5. **Laci Navigasi Geser ("Push Drawer") (`HistoryDrawer.kt`, `MainActivity.kt`)**:
   - Mekanisme laci bergeser (*push drawer*) tanpa scrim gelap: saat laci (`#F1F1EF`, lebar `82%`) terbuka, layar utama bergeser ke kanan dengan sudut membulat `32 dp` dan garis tepi `#C6C6C4` sehingga sisi kiri layar utama mengintip di tepi kanan persis seperti di video.
   - Judul serif **`"Claude"`**, empat menu (`Chat`, `Proyek`, `Code`, `Artefak`) dengan ikon kustom 1:1 (`DrawerChatGlyph`, `ProyekGlyph`, `CodeGlyph`, `ArtefakGlyph`), seksi `"Terbaru"` dengan ikon gelembung tunggal (`BubbleGlyph`) per baris, serta bilah bawah mengambang bergradien halus dengan avatar bulat (`ic_drawer_avatar.png`) di kiri dan tombol hitam **`+ Chat baru`** di kanan.

6. **Layar Input Suara (`VoiceSheet` di `Sheets.kt`)**:
   - Mengetuk tombol mikrofon pada kartu input membuka lembar penuh **`VoiceSheet`** dengan latar `#F8F8F6` yang memudar ke pendaran hangat `#F3E4DE` / `#EDD7CF` di bagian bawah, tombol tutup bulat `X` di kiri atas, ilustrasi mikrofon (`ic_voice_mic.png`), judul serif `"Kirim pesan ke Claude menggunakan suara Anda."`, subjudul `"Mengobrol lebih cepat dan alami"`, pemilih bahasa `"Indonesia ⌄"`, dan tombol hitam penuh **`"Lanjutkan"`** di bagian bawah.
