# v64: pola gerak tambahan untuk bola oranye (`LoaderDots`)

- Pola bertambah dari 16 menjadi **22**. Pola 0-15 tidak diubah; peralihan antar pola tetap dihaluskan (blend 30%).
- Pola baru (`dotPattern` 16-21):
  - **16 Denyut**: tiga bola berdenyut bergantian dari kiri ke kanan seperti gelombang sonar.
  - **17 Ular**: tiga bola mengikuti kepala di lintasan gelombang lebar; kepala besar, ekor makin kecil.
  - **18 Tata surya**: satu bola besar di pusat, satu mengorbit cepat di dalam, satu mengorbit pelan di luar.
  - **19 Elips berputar**: tiga bola menyusuri elips yang ikut berputar.
  - **20 Ombak lompat**: bola melompat satu per satu seperti gelombang suporter.
  - **21 Pingpong**: bola kecil memantul di antara dua bola ujung yang membesar sesaat saat terkena.
- Siklus penuh kini 33 detik (22 x 1,5 dtk).
- Tes: `hasSixteenPatterns` menjadi `hasTwentyTwoPatterns`; tes baru `patternsAddedInV64LoopSeamlessly`. Tes kanvas dan kemulusan yang ada otomatis ikut memeriksa pola baru (batas kanvas, jari-jari 1,0-2,6, langkah maks 1 dp per 0,01 u untuk pola 7 ke atas).
- Belum dikompilasi dan belum dilihat di perangkat.
