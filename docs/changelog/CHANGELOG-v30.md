# v30 — CI untuk Mythos dan titik pulih per tugas

> Seperti v29, **belum pernah dikompilasi** (tanpa Kotlin/Gradle di lingkungan pembuat). Logika murni titik pulih (LineDiff, jurnal, diff,
> pembatalan, konflik, pemangkasan, root symlink) diuji dengan porting Java: 31 asersi lulus. Kode Compose hanya ditinjau baris demi baris.
> Jalankan CI (atau `gradle :app:testDebugUnitTest`) lebih dulu.

## 1. CI untuk Mythos sendiri
`.github/workflows/ci.yml`: tes unit, lint (belum memblokir), APK debug, laporan sebagai artefak. Membuat stub `EmbeddedKeys.kt` kosong bila
file aslinya tidak ada (file itu di-`.gitignore`; tanpa stub checkout bersih tak bisa dikompilasi). Hanya `main`/`master`, PR, dan manual.

## 2. Titik pulih per tugas
| Bagian | Berkas |
|---|---|
| Jurnal copy-on-write (`TaskJournal`), `Checkpoint` (daftar perubahan, diff, pembatalan), `CheckpointStore` (pangkas 15), `LineDiff` | agent/Checkpoints.kt (baru) |
| Hook `env.journal?.beforeChange(f)` sebelum mutasi di Write, Edit (buat + ubah), Delete; `ToolEnv.journal` | agent/Tools.kt |
| Jurnal diteruskan ke agen | agent/Agent.kt, agent/MythosAgent.kt |
| `ChangesItem` + simpan/muat di riwayat | ui/ChatModels.kt, ui/ChatStore.kt |
| Jurnal per tugas, kartu perubahan setelah tugas (juga bila dihentikan/gagal), alur batalkan, pemuat diff, hapus titik pulih bersama riwayat, pembersihan incognito | ui/AgentViewModel.kt |
| Kartu "N file diubah +A −R": daftar file, diff berwarna, tombol "Batalkan tugas ini" | ui/ChangesCard.kt (baru), ui/ChatScreen.kt |
| Dialog konfirmasi batalkan | MainActivity.kt |

**Bug yang ditemukan saat meninjau (diperbaiki sebelum rilis):** `filesDir` Android adalah symlink (`/data/user/0/…` → `/data/data/…`) sedangkan sandbox
memakai path kanonik; jurnal yang menerima root non-kanonik akan diam-diam tidak mencatat apa pun di perangkat nyata (tes JVM tidak akan menangkapnya).
`TaskJournal` kini mengkanonikkan root-nya sendiri, dengan tes `journalWorksWhenTheRootIsASymlink`.

## Tes baru
`CheckpointsTest` (12 tes: alat → jurnal, diff, pembatalan bersih, konflik dilewati, hanya sekali, tanpa jejak, biner, symlink, pangkas, LineDiff),
`AgentLoopTest.journalRecordsWhatTheAgentChanged`, `ChatStoreTest.changesItemRoundTrips`.

## Cek pertama kali
1. Dorong ke GitHub (cabang `main`) dan lihat CI: kompilasi + tes. Perbaiki galat kompilasi yang muncul (berkas paling berisiko: `ChangesCard.kt`, `AgentViewModel.kt`).
2. Di perangkat: beri tugas yang mengubah file → kartu perubahan muncul → buka diff → "Batalkan tugas ini" → file kembali; ubah file lagi lalu batalkan tugas lama → file itu dilewati.
3. Putar layar / tutup aplikasi lalu buka riwayat: kartu perubahan tetap ada dan pembatalan masih bisa.
