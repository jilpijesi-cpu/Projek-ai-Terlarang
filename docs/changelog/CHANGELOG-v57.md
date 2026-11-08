# v57 — sub-agen paralel + perbaikan jalur proyek Android
- Agent.kt: `runBatch` — beberapa panggilan `Task` yang berurutan dalam satu balasan kini dijalankan BERSAMAAN (maks. 3 sekaligus, maks. 6 per balasan; sisanya ditolak dengan pesan jelas). Alat lain tetap berurutan. Hasil selalu dikembalikan sesuai urutan panggilan.
- Models.kt: `AgentEvent.ToolEnd` punya field `summary` (opsional) agar UI mencocokkan baris yang tepat saat beberapa Task selesai tak berurutan.
- AgentViewModel.kt: ToolEnd dicocokkan lewat summary lebih dulu.
- Protocol.kt: prompt sistem memberi tahu model bahwa beberapa Task dalam satu balasan berjalan paralel (untuk area independen saja).
- Tes baru: ParallelTaskTest (bukti konkurensi lewat latch, urutan hasil, batas 6). Belum dikompilasi: jalankan `./gradlew testDebugUnitTest`.
- Tampilan: sub-agen (Task) TIDAK lagi menambah baris langkah. Statusnya tampil di baris kecil yang sama dengan "Masih diproses…" (11,5 sp, abu-abu, ukuran tetap): teksnya berganti antar sub-agen yang sedang bekerja ("Sub-agen: audit A · 1/3 selesai") dan muncul tanpa menunggu 10 detik. Bila tidak ada sub-agen, baris itu tetap "Masih diproses…" seperti sebelumnya.
- Jarak baris kecil ke baris aktivitas dirapatkan (naik 20 dp; sebelumnya ±54 dp dari teks utama, kini ±26 dp).
- Catatan "Menjalankan N sub-agen paralel" dihapus (digantikan baris kecil itu).
- Timeline.kt: `SubAgentStatus` + `subAgentLabel`; Tes baru: SubAgentStatusTest.

## Perbaikan dari log agen nyata (build Android pertama)
Agen harus memperbaiki 7 masalah sendiri; akar penyebabnya di kode/prompt, kini diperbaiki di sumbernya:
- Tools.kt `ProjectSandbox.resolve`: awalan NAMA folder proyek (`proyek-2026.../app/x`) dibuang otomatis (kecuali ada subfolder sungguhan bernama sama). Dulu semua file bersarang satu tingkat terlalu dalam dan agen harus memindahkan 17 file ke akar.
- Protocol.kt `firstMessage`: menegaskan semua path relatif terhadap folder kerja dan nama foldernya BUKAN bagian path.
- GitHubActions.kt `hint`: resep Android pasti — jangan buat gradlew/gradle-wrapper, workflow memakai `gradle` + setup-gradle, tabel kecocokan AGP↔Gradle (8.3→8.4, 8.4→8.6, 8.5/8.6→8.7, 8.7→8.9) dengan versi yang SAMA di workflow, `pluginManagement`/`dependencyResolutionManagement` dengan google(), hanya Kotlin DSL, ikon peluncur harus ada atau `android:icon` dihilangkan.
- GitHubActions.kt `collect`: `gradle-wrapper.jar` berukuran 0 byte tidak dikirim (selalu gagal di validasi wrapper).
- Tes baru: SandboxPathTest, emptyGradleWrapperJarIsNotPushed. Belum dikompilasi.

## Kunci OpenRouter tambahan
- EmbeddedKeys.kt: 8 kunci OpenRouter baru ("OpenRouter Free 8" s.d. "15"), model `openrouter/free`, `locked = true`, diletakkan setelah "OpenRouter Free 7". Total kunci tertanam: 15 -> 23 (label "Otomatis 23 kunci").

## Mode Otomatis hanya OpenRouter
- EmbeddedKeys.kt: `all` = semua kunci tersimpan (tidak ada yang dihapus); `specs` (pool Otomatis) = hanya kunci OpenRouter (1 "OpenRouter" + 15 "OpenRouter Free" = 16). Kunci Gemini, Groq, Mistral, NVIDIA, Cohere, OpenAI tetap di file tetapi tidak dipakai Otomatis. Label menjadi "Otomatis 16 kunci".
- Tes baru: EmbeddedKeysAutoTest.
