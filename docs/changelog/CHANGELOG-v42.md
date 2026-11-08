# v42 — Tempo streaming disamakan dengan rekaman acuan
Diukur dari rekaman layar (10 bingkai/detik):
- Teks mengalir lebih cepat: REVEAL_CPS 42 -> 130 huruf/detik (dipercepat sebanding antrean). Narasi 86 huruf tuntas ±0,6 dtk, 190 huruf ±1,2 dtk (rekaman: ±0,7 dan ±1,0 dtk).
- Antrean memakai perkiraan lama alir yang sebenarnya (`revealDurationMs`), bukan huruf/CPS.
- Jeda narasi -> baris aktivitas 800 -> 300 ms; kelompok langkah tampil min. 1500 -> 1400 ms; jeda blok penutup 600 -> 300 ms.
- Label aktivitas: tahan minimal 1100 -> 700 ms; pergantian label 250/200 ms (sebelumnya 400/300); titik -> ikon jam 200 ms (sebelumnya 400).
- Tes baru di PaceTest: lama alir sesuai rekaman dan jeda antarblok pendek.
Belum dikompilasi/diuji di perangkat: jalankan `./gradlew testDebugUnitTest` lalu coba di HP.
