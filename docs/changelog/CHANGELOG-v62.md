# v62 — Kejujuran penyedia + penanganan batas token (HTTP 413)

- **Kejujuran (prioritas tertinggi)**: system prompt punya bagian `# Honesty`: model dilarang mengarang fakta/sumber, mengaku sebagai model/perusahaan lain, menjelaskan alasan pemilihan penyedia, atau mengaku sudah membaca/menjalankan sesuatu tanpa bukti hasil alat.
- **Fakta runtime**: `FailoverClient` menambahkan model dan host penyedia yang SUNGGUH dipakai (`engineNote`) ke system prompt tiap panggilan, jadi model tidak perlu menebak jati dirinya.
- **413 tidak lagi langsung gagal (tanpa kartu baru di layar)**: `Agent.decide` memperkecil konteks (`ContextManager.shrunkBudget` membaca "Limit X, Requested Y") lalu mencoba lagi sampai 3x; `ContextManager.squeeze` memotong pesan terpanjang (mis. isi lampiran besar), dan model tidak diberi tahu bagian yang dipotong secara diam-diam: ada penanda `[... N karakter dipotong ...]`.
- Percobaan perkecil konteks dihitung per panggilan model (bukan per tugas), supaya tugas panjang tidak gagal karena 413 yang tersebar.
- Layar menampilkan ringkasan 413 (batas vs permintaan) tanpa JSON mentah/ID organisasi penyedia.
- Bila tetap tidak muat, pesan galat menjelaskan sebabnya dan saran tindakan.
- Catatan router: untuk model dikunci (mis. openrouter/free) fakta runtime menyatakan model sebenarnya dipilih penyedia dan tidak diketahui model.
- Tes baru: `HonestyAndLimitTest`.
- **Aturan sumber/referensi** (`# Sources and references` di system prompt): sebut URL/path tiap klaim, jangan mengarang URL/kutipan, utamakan sumber primer, cari minimal dua sumber independen untuk hal penting, jujur bila hanya satu/nol, bedakan halaman yang DIBUKA vs hanya cuplikan pencarian, sebut tanggal sumber untuk fakta yang bisa berubah.
- **Sub-agen punya akses web**: `WebSearch` + `WebFetch`. Atas permintaan pengguna, `WebFetch` kini `Risk.SAFE`: agen utama DAN sub-agen membuka halaman tanpa meminta izin (label di Konektor diubah menjadi "halaman dibuka otomatis tanpa izin"). Pengaman alamat privat/lokal dan label "data tak tepercaya" tetap berlaku.
- Sub-agen kini mengikuti sakelar Pencarian web (sebelumnya tetap punya WebSearch walau dimatikan) dan memakai kunci Brave/Tavily pengguna (sebelumnya tanpa kunci). Laporan sub-agen diminta memuat URL dan status "dibuka/cuplikan".
- Tes baru: `SubAgentWebTest`; `WebTest` diperbarui (WebFetch kini SAFE).

## Penyedia "Otomatis" (kunci tertanam) dihapus
- Penyedia "Otomatis" beserta `EmbeddedKeys.kt`, `AutoKeys.kt`, dan tesnya dihapus dengan sengaja. Tidak ada lagi kunci yang tertanam di APK.
- Pemilihan model otomatis kini ditangani per penyedia: kolom model dikosongkan, lalu `ModelResolver` mengambil daftar model dari penyedia yang dipilih, mengurutkan, mengetes, dan memakai yang terbaik; `FailoverClient`/`KeyPool` mengganti model atau kunci yang gagal di latar belakang. Chip model menampilkan "Otomatis" selama kolom model kosong.
- Catatan v61 yang menyebut "Mode Otomatis tidak berubah" sudah tidak berlaku. README dan CI dirapikan (step stub `EmbeddedKeys.kt` dihapus).
- Versi dinaikkan ke `versionCode 62` / `1.0.62`.
