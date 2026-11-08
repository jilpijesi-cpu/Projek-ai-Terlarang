# v31 — alur streaming aktivitas AI seperti rekaman

Basis: v30 (ci-titik-pulih). Acuan: rekaman layar aplikasi Claude (1080×2436, 90 detik).

## Akar masalah di v30
- `LlmClient.complete()` menunggu seluruh balasan: tidak ada streaming sungguhan.
- `ChatScreen` menyembunyikan narasi dan jawaban akhir saat agen bekerja. Yang tampil hanya satu baris hidup dengan label buatan dan antrean
  tampilan yang menunda blok, lalu jawaban diketik setelah semuanya selesai. Karena itu "live streaming" sering terasa tidak muncul.

## Perubahan
**Lapisan model (`agent/Llm.kt`, `agent/KeyPool.kt`)**
- `LlmClient.stream(system, messages, onText)` dengan bawaan jatuh ke `complete()`; klien lama dan tes lama tidak perlu diubah.
- SSE untuk OpenAI-compatible (`stream:true`), Anthropic (`content_block_delta`), dan Gemini (`:streamGenerateContent?alt=sse`).
  Bila server membalas JSON biasa, badan diurai seperti `complete()`.
- `Http.postSse`: `Accept-Encoding: identity` (gzip menahan potongan), timeout baca = jeda antarpotongan.
- `VisionGate`, `RetryingClient`, `FailoverClient` ikut streaming; teks parsial dibuang (`onText("")`) sebelum coba ulang / ganti kunci.
- Fungsi murni yang bisa diuji: `SseParser`, `readSse`, `TextStream`, `openAiDelta`, `anthropicDelta`, `geminiDelta`, `*Whole`.

**Agen (`agent/Agent.kt`, `Protocol.kt`, `Models.kt`)**
- Event baru `AgentEvent.Streaming(text, tool, target)`; `Protocol.visibleText` dan `Protocol.pendingTool` membaca balasan yang belum selesai.
- `streamReply` memakai `Channel.CONFLATED` agar jaringan tidak menunggu UI; pratinjau percobaan yang gagal dibuang sebelum catatan perbaikan format.

**UI (`ui/`)**
- `Timeline.kt` (baru, murni Kotlin): `TimelineReducer`, `toBlocks`, `liveLabel`, `groupTitle`, `groupIcon`, `progressKey`.
- `ThoughtItem.live`; `LiveActivity`; `AgentViewModel.liveActivity`.
- `ChatScreen.kt` ditulis ulang: tanpa antrean tampilan dan label palsu; narasi dan jawaban mengalir (serif, ujung memudar); baris aktivitas
  (titik → ikon jam/terminal, label berganti di tempat); "Masih diproses…"; tombol "ke bawah".
- `Design.kt`: `rememberPhase` (jam bingkai absolut), `rememberRevealed`, `withTailFade`, `assistantStyle`, `TerminalGlyph`, `ArrowDownGlyph`,
  chip kode inline (teks #184F93 di atas #ECECE9).

## Terukur dari rekaman (1 dp = 3 px)
Jarak baris teks 66 px = 22 dp; margin teks ±60 px = 20 dp; lebar rata-rata ±7,3 dp/huruf (serif ±14,5 sp); paragraf → baris aktivitas 45,3 dp
pusat ke pusat; baris → paragraf 48,7 dp; "Masih diproses…" 11,5 sp, rata dengan awal label (x = 171 px), 53,8 dp di bawah pusat baris, muncul ±10 detik
setelah label terakhir berganti; ikon terminal dan label berganti saat perintah selesai.

## Tes baru
`StreamingTest` (SSE, tiap penyedia, pembatas laju, teks parsial, coba ulang, failover), `AgentStreamingTest` (urutan event, blok alat tidak bocor,
pratinjau dibuang saat perbaikan format), `TimelineTest` (reducer, blok, label, tanda kemajuan).

## Status verifikasi — BELUM DIKOMPILASI
Tidak ada Kotlin/Gradle di lingkungan penyusunan. Yang sudah dicek: port Java untuk `visibleText`, `pendingTool`, pembaca SSE, dan kecepatan reveal;
keseimbangan kurung semua berkas yang diubah; tinjauan diff manual. Jalankan CI (`testDebugUnitTest`, `lintDebug`, `assembleDebug`) lebih dulu.

Uji manual di perangkat: (1) obrolan biasa dengan tiap penyedia, teks harus mengalir bertahap; (2) tugas berlapis alat, baris aktivitas berpindah
titik → jam; (3) tombol Berhenti di tengah aliran; (4) penyedia yang tidak streaming (teks muncul dikejar mulus); (5) model lambat ≥10 detik → "Masih diproses…".
