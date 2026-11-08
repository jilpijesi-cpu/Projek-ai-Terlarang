# v65: kedipan saat balasan mulai mengalir

Dasar temuan: tiga rekaman layar (1000244556, 1000244562, 1000244563). Kedipan berasal dari gerakan layar, bukan dari huruf yang diketik.

- `ChatScreen.kt`, sematan pesan baru: `animateScrollToItem(idx)` yang langsung disusul `scrollToItem(idx)` (dua lompatan berturut-turut) diganti satu gulir halus. Koreksi setelah ruang kosong di ujung terhitung hanya dijalankan bila daftar memang belum tepat di awal item (`atItemTop`), jadi tidak ada lompatan tambahan dan tidak ada gulir ulang pada tiap perubahan `rawTail` saat teks tumbuh.
- `ChatScreen.kt`, `follow` (mengikuti teks ke bawah): sebelumnya meluncurkan satu coroutine `scrollBy(4000f)` per 3 bingkai dan menumpuk; kini hanya satu gulir berjalan pada satu waktu (`followJob`).
- `ChatScreen.kt`, pergantian baris "Memahami permintaan" / "Proses berpikir" ke teks: baris itu kini memudar 160 ms alih-alih hilang seketika, dan item teks bergeser ke posisi barunya dengan animasi 200 ms (`animateItem`), sehingga tidak ada lompatan ±7 px.
- Kecepatan ketik (`REVEAL_CPS = 130`) TIDAK diubah: nilainya dikalibrasi terhadap rekaman acuan (`PaceTest.revealDurationMatchesReferenceRecording`) dan Anda meminta tampilan 1:1 dengan acuan itu.
- Belum dikompilasi dan belum dilihat di perangkat. `Modifier.animateItem` memerlukan Compose foundation 1.7 (BOM 2024.09.00 sudah memenuhi).
