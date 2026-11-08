package com.mythos.app.agent

import java.io.File

/**
 * Playbook = berkas teks (.md/.txt) berisi aturan satu sektor atau negara: regulator, urutan eskalasi, saluran resmi,
 * dokumen wajib, tenggat, dan hal yang harus dihindari. Dibaca dari folder `playbook/` di proyek, atau dari
 * `uploads/playbook*.md|txt` bila dilampirkan lewat obrolan. Agen menampilkannya di awal tiap tugas, sehingga
 * mengganti atau menambah sektor cukup dengan mengedit berkas, tanpa mengubah kode.
 */
object Playbooks {
    const val DIR = "playbook"
    private const val MAX_FILES = 8
    private const val MAX_FILE_CHARS = 6_000
    private const val MAX_TOTAL_CHARS = 14_000
    private val EXT = setOf("md", "txt")

    /** Kerangka untuk playbook baru; agen memakainya saat pengguna minta menambah sektor. */
    const val TEMPLATE = """# Playbook: <sektor / negara>
Berlaku untuk: <jenis sengketa, mis. klaim asuransi, pesanan marketplace, pajak>

## Urutan eskalasi
1. <penyedia layanan: kanal, nomor tiket yang dicatat>
2. <regulator atau lembaga sengketa pertama>
3. <langkah berikutnya>

## Saluran resmi (periksa ulang di situs resminya sebelum dipakai)
- <nama lembaga>: <situs resmi>; <email / portal / telepon>

## Dokumen yang biasanya diminta
- <daftar>

## Tenggat yang berlaku
- <mis. penyedia wajib menjawab dalam N hari kerja>

## Jangan
- <hal yang tidak boleh diklaim atau dilampirkan>
"""

    data class BundledPlaybook(
        val id: String,
        val title: String,
        val keywords: Regex,
        val content: String,
    )

    /** Playbook bawaan Indonesia yang terverifikasi untuk membantu kasus uang tertahan di e-commerce, sistem pembayaran BI, dan jasa keuangan OJK. */
    val BUNDLED: List<BundledPlaybook> = listOf(
        BundledPlaybook(
            id = "ecommerce-dana-tertahan",
            title = "Playbook Bawaan: Dana / Saldo Tertahan di E-Commerce & Marketplace Indonesia",
            keywords = Regex("shopee|tokopedia|tiktok\\s*shop|lazada|blibli|bukalapak|marketplace|e-?commerce|penjual|pembeli|pesanan|retur|komplain pesanan|saldo penjual|dana tertahan", RegexOption.IGNORE_CASE),
            content = """# Playbook: E-Commerce & Marketplace Indonesia (Dana / Saldo Tertahan)
Berlaku untuk: Saldo penjual/pembeli dibekukan, pengembalian dana (refund) macet, sengketa barang rusak/tidak sampai, atau penahanan dana sepihak di aplikasi e-commerce.

## Urutan eskalasi
1. Pusat Resolusi / Layanan Pengaduan Internal Platform: wajib catat Nomor Tiket / ID Laporan, tanggal pengajuan, dan nama agen CS.
2. Surat Keberatan Resmi tertulis ke Manajemen Kepatuhan / Legal Platform (dengan lampiran PDF bundel bukti & tenggat tanggapan 3–7 hari kerja).
3. Direktorat Jenderal Perlindungan Konsumen dan Tertib Niaga (Ditjen PKTN) Kementerian Perdagangan RI (berdasarkan UU No. 8 Tahun 1999 tentang Perlindungan Konsumen & PP No. 80 Tahun 2019 tentang PMSE) atau Badan Penyelesaian Sengketa Konsumen (BPSK).
4. Jika dana tertahan berada pada dompet elektronik berizin Bank Indonesia (mis. ShopeePay, GoPay, OVO, DANA), eskalasi paralel ke Bank Indonesia (BI Bicara 131).

## Saluran resmi (verifikasi ulang dengan WebSearch/WebFetch sebelum pengiriman)
- Ditjen PKTN Kemendag: situs `simpktn.kemendag.go.id` / WhatsApp Pengaduan Konsumen Ditjen PKTN `0853-1111-1010` / email `pengaduan.konsumen@kemendag.go.id`.
- BPSK (Badan Penyelesaian Sengketa Konsumen): sesuai domisili konsumen.

## Dokumen yang biasanya diminta
- Bukti transaksi / invoice resmi dari aplikasi dan mutasi rekening / e-wallet yang menunjukkan pemotongan atau penahanan dana.
- Tangkapan layar kronologi Pusat Resolusi & percakapan CS yang memuat nomor tiket dan alasan penahanan.
- Bukti pengiriman (resi, foto/video unboxing atau packing tanpa jeda bila terkait fisik barang).
- Ringkasan kronologi bertanggal dan perhitungan nilai kerugian/dana tertahan (`KASUS.md` & PDF `MakePdf`).

## Tenggat yang berlaku
- SLA Pusat Resolusi platform umumnya 2x24 jam s.d. 7 hari kerja; penahanan saldo investigasi wajib memiliki dasar pasal Syarat & Ketentuan yang spesifik serta batas waktu jelas.

## Jangan
- Jangan mencantumkan NIK 16 digit utuh di badan email terbuka (cukup di lampiran PDF resmi bila diminta regulator).
- Jangan menggunakan kata tuduhan pidana ("menggelapkan", "mencuri") sebelum ada putusan; gunakan rumusan fakta objektif ("dana sebesar Rp ... belum dikembalikan melewati SLA ...")."""
        ),
        BundledPlaybook(
            id = "bi-sistem-pembayaran-fintech",
            title = "Playbook Bawaan: Transfer Antarbank, BI-FAST, Payment Gateway & Dompet Digital (Bank Indonesia)",
            keywords = Regex("flip|dana|ovo|gopay|shopeepay|linkaja|qris|bi-?fast|transfer|mutasi|bank indonesia|\\bbi\\b|bicara@bi|payment gateway|top\\s*up", RegexOption.IGNORE_CASE),
            content = """# Playbook: Sistem Pembayaran, Transfer Dana & Dompet Digital (Kewenangan Bank Indonesia)
Berlaku untuk: Transfer gagal tapi saldo terpotong, refund transfer tertahan di penyelenggara transfer dana (mis. Flip), pembekuan saldo e-wallet (DANA/OVO/GoPay/LinkAja/ShopeePay), atau sengketa QRIS/BI-FAST.

## Urutan eskalasi
1. Pengaduan resmi ke Penyelenggara Jasa Pembayaran (PJP) terkait: minta Nomor Tiket Pengaduan dan penjelasan tertulis status transaksi/rekening.
2. Surat Tindak Lanjut / Keberatan ke unit penanganan pengaduan PJP bila melewati SLA awal atau bila jawaban CS tidak konsisten dengan bukti mutasi.
3. Eskalasi ke Bank Indonesia (Perlindungan Konsumen Sistem Pembayaran — PBI No. 22/20/PBI/2020 & PADG Perlindungan Konsumen).

## Saluran resmi (verifikasi ulang dengan WebSearch/WebFetch sebelum pengiriman)
- Bank Indonesia (BI Bicara 131): email `bicara@bi.go.id`, portal `bicara131.bi.go.id`, telepon `131` (lokal).

## Dokumen yang biasanya diminta
- Bukti transfer / struk transaksi dan mutasi rekening pengirim serta penerima (PDF/gambar asli tanpa edit).
- Nomor tiket pengaduan di penyelenggara dan tangkapan layar jawaban akhir (atau bukti tidak ada penyelesaian hingga batas waktu).
- Surat pengaduan resmi yang merinci tanggal, nominal, nomor referensi, dan ketidakkonsistenan jawaban penyelenggara.

## Tenggat yang berlaku
- Penyelenggara Jasa Pembayaran wajib menindaklanjuti pengaduan tertulis paling lama 20 hari kerja sejak dokumen lengkap diterima (dapat diperpanjang dalam kondisi tertentu sesuai ketentuan BI dengan pemberitahuan tertulis).

## Jangan
- Jangan mengedit/memberi watermark pada file bukti mutasi asli yang dilampirkan.
- Jangan menulis email gabungan untuk dua lembaga berbeda dalam satu kolom Kepada: buat draf terpisah untuk penyelenggara dan regulator."""
        ),
        BundledPlaybook(
            id = "ojk-perbankan-asuransi-pinjol",
            title = "Playbook Bawaan: Perbankan, Kartu Kredit, Paylater, Pinjol & Asuransi (OJK APPK)",
            keywords = Regex("\\bojk\\b|kontak\\s*157|appk|kartu kredit|paylater|kredivo|akulaku|spaylater|pinjol|pindar|asuransi|klaim|polis|kredit|slik|rekening diblokir|bank", RegexOption.IGNORE_CASE),
            content = """# Playbook: Sektor Jasa Keuangan — Perbankan, Pembiayaan/Paylater, Pinjol & Asuransi (Kewenangan OJK)
Berlaku untuk: Sengketa rekening bank, debet otomatis tidak sah, tagihan kartu kredit/paylater, restrukturisasi/SLIK, penolakan klaim asuransi, atau penagihan pinjaman daring (PUJK).

## Urutan eskalasi
1. Pengaduan internal ke Pelaku Usaha Jasa Keuangan (PUJK): wajib memperoleh tanda terima / Nomor Tiket Pengaduan.
2. Eskalasi melalui Aplikasi Portal Perlindungan Konsumen (APPK) Otoritas Jasa Keuangan (OJK) sesuai POJK No. 22 Tahun 2023 tentang Pelindungan Konsumen dan Masyarakat di Sektor Jasa Keuangan.
3. Fasilitasi OJK atau penyelesaian sengketa melalui LAPS SJK (Lembaga Alternatif Penyelesaian Sengketa Sektor Jasa Keuangan).

## Saluran resmi (verifikasi ulang dengan WebSearch/WebFetch sebelum pengiriman)
- Portal APPK OJK: `kontak157.ojk.go.id`, email `konsumen@ojk.go.id`, telepon `157`, WhatsApp `081-157-157-157`.
- LAPS SJK: `lapssjk.id`.

## Dokumen yang biasanya diminta
- Identitas pengadu (di dalam lampiran PDF resmi, bukan di badan email terbuka), bukti tanda terima pengaduan dari PUJK, tanggapan PUJK (atau bukti lewat batas SLA), mutasi/tagihan/polis, dan kronologi tertulis.

## Tenggat yang berlaku
- PUJK wajib menyelesaikan pengaduan tertulis paling lambat 10 hari kerja (untuk pengaduan tertentu) atau 20 hari kerja sejak dokumen lengkap diterima (POJK 22/2023). Tanggapan keberatan konsumen terhadap jawaban PUJK diajukan dalam batas waktu APPK.

## Jangan
- Jangan membocorkan OTP, PIN, atau CVV di dalam dokumen mana pun."""
        )
    )

    /** Berkas playbook yang ditemukan, urut nama, paling banyak [MAX_FILES]. */
    internal fun files(root: File): List<File> {
        val found = ArrayList<File>()
        File(root, DIR).listFiles()?.filterTo(found) { it.isFile && it.extension.lowercase() in EXT && !it.name.startsWith(".") }
        File(root, "uploads").listFiles()?.filterTo(found) {
            it.isFile && it.name.lowercase().startsWith("playbook") && it.extension.lowercase() in EXT
        }
        return found.sortedBy { it.name.lowercase() }.take(MAX_FILES)
    }

    /** Teks untuk konteks agen, atau null bila tidak ada playbook di proyek. */
    fun render(root: File): String? {
        val fs = files(root)
        if (fs.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("Playbook files (rules per sector or country written by the user or by you earlier; they OVERRIDE the default Indonesian BI/OJK assumptions for the matching case; if none matches the case, say so and use the default rules; still verify contact details on the official sites):\n")
        for (f in fs) {
            if (sb.length >= MAX_TOTAL_CHARS) { sb.append("\n(playbook lain dilewati: batas ukuran)"); break }
            val text = runCatching { f.readText() }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) continue
            sb.append("\n--- ").append(f.relativeTo(root).path.replace('\\', '/')).append(" ---\n").append(clip(text, MAX_FILE_CHARS)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * Bila proyek belum memiliki berkas `playbook/<nama>.md` kustom, cari playbook bawaan Indonesia yang cocok dengan teks tugas/kasus.
     */
    fun renderBundledForTask(root: File, taskAndMemory: String): String? {
        if (files(root).isNotEmpty()) return null
        val matched = BUNDLED.filter { it.keywords.containsMatchIn(taskAndMemory) }
        if (matched.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("Built-in Indonesian Sector Playbooks (verified escalation workflow for this dispute; still verify latest official email/URL with WebSearch/WebFetch):\n")
        for (b in matched.take(2)) {
            sb.append("\n--- [Bundled] ").append(b.id).append(" ---\n").append(clip(b.content, MAX_FILE_CHARS)).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
