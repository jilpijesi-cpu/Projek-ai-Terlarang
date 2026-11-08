# v41 — Sub-agen (alat Task) ala Claude Code
- Alat baru `Task`: agen utama menyerahkan penelitian ke sub-agen read-only (LS/Glob/Grep/Read/WebSearch) dengan konteks baru, maks. 50 langkah, lalu menerima satu laporan ringkas.
- Sub-agen tidak bisa menulis/menghapus/build/bertanya/memanggil Task lagi; berjalan diam-diam, layar hanya menampilkan "Sub-agen: <label>".
- Prompt sistem mengarahkan agen memakai Task untuk riset luas. Tes baru: TaskToolTest.
- Batas dilonggarkan (tanpa pertimbangan biaya): langkah per tugas 60 -> 120, langkah sub-agen 25 -> 50, gambar dikirim ulang di 12 panggilan pertama (sebelumnya 6).
