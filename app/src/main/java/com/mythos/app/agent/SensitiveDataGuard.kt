package com.mythos.app.agent

/**
 * Penjaga keamanan data sensitif dan anti-prompt-injection untuk konten dari luar (email, web, dokumen lampiran, log).
 *
 * 1. Menyamarkan kode OTP, PIN, CVV/CVC, nomor kartu pembayaran 16 digit (valid Luhn), serta kata sandi/token Bearer
 *    agar tidak bocor ke konteks model LLM, log audit, atau keluar melalui alat.
 * 2. Menetralkan tag protokol internal (`</tool_result>`, `<tool_use>`, `</tool_use>`, `<system-reminder>`, `</system-reminder>`)
 *    di dalam data eksternal supaya halaman web, dokumen, atau email tidak dapat memalsukan instruksi sistem atau memutus blok hasil alat.
 */
object SensitiveDataGuard {

    // Pola OTP / Kode Verifikasi: kata kunci diikuti 4-8 angka dalam jarak dekat.
    private val OTP_CONTEXT_RE = Regex(
        """(?i)\b(otp|kode\s+verifikasi|verification\s+code|kode\s+rahasia|security\s+code|passcode|kode\s+masuk|one[-\s]?time\s+password|jangan\s+berikan\s+kode)\b([^\n\r]{0,40}?)\b(\d{4,8})\b"""
    )

    // Pola terbalik: angka 4-8 digit diikuti "adalah kode OTP / verifikasi Anda"
    private val OTP_REVERSE_RE = Regex(
        """\b(\d{4,8})\b([^\n\r]{0,30}?)\b(?i)(adalah\s+kode\s+(?:otp|verifikasi|rahasia)|is\s+your\s+(?:otp|verification|security)\s+code)\b"""
    )

    // Pola CVV / CVC / PIN eksplisit
    private val CVV_PIN_RE = Regex(
        """(?i)\b(cvv2?|cvc2?|pin(?:\s+(?:atm|kartu|transaksi|m-?banking))?)\s*(?:[:=]|adalah|is)\s*(\d{3,6})\b"""
    )

    // Pola kata sandi / Bearer token eksplisit
    private val PASSWORD_BEARER_RE = Regex(
        """(?i)\b(password|kata\s+sandi|passphrase)\s*[:=]\s*([^\s"',;]{4,64})"""
    )

    private val BEARER_TOKEN_RE = Regex(
        """(?i)(Authorization\s*:\s*Bearer\s+)([A-Za-z0-9._\-]{16,})"""
    )

    // Pola token OAuth 2.0 Google (ya29., 1//), GitHub PAT (ghp_, github_pat_), dan kunci API AI (sk-, AIza, hf_, gsk_, tvly-)
    private val API_TOKEN_SECRET_RE = Regex(
        """\b(ya29\.[A-Za-z0-9._\-]{12,}|1//[A-Za-z0-9._\-]{12,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9._\-]{16,}|AIza[A-Za-z0-9._\-]{30,}|hf_[A-Za-z0-9]{16,}|gsk_[A-Za-z0-9]{16,}|tvly-[A-Za-z0-9._\-]{16,}|GOCSPX-[A-Za-z0-9._\-]{16,})\b"""
    )

    // Pola field JSON/query access_token, refresh_token, client_secret, api_key
    private val JSON_TOKEN_RE = Regex(
        """(?i)("(?:access_token|refresh_token|client_secret|api_key|id_token)"\s*:\s*")([^"]{8,})(")"""
    )

    // Kandidat 16 digit kartu pembayaran (dengan spasi atau strip opsional per 4 digit)
    private val CARD_CANDIDATE_RE = Regex(
        """(?<!\d)(\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4})(?!\d)"""
    )

    /**
     * Luhn check untuk membedakan nomor kartu kredit/debit 16 digit berformat "xxxx xxxx xxxx xxxx"
     * dari nomor referensi biasa.
     */
    internal fun passesLuhn(digitsOnly: String): Boolean {
        if (digitsOnly.length != 16 || !digitsOnly.all { it.isDigit() }) return false
        // Hindari positif palsu seperti 0000... atau angka berulang sederhana
        if (digitsOnly.toSet().size <= 2) return false
        var sum = 0
        for (i in digitsOnly.indices) {
            var d = digitsOnly[15 - i] - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** Samarkan OTP, CVV, PIN, kata sandi, Bearer token, OAuth token, API key, dan nomor kartu pembayaran. */
    fun redactSensitiveCredentials(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = OTP_CONTEXT_RE.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[OTP-DISEMBUNYIKAN]"
        }
        out = OTP_REVERSE_RE.replace(out) { m ->
            "[OTP-DISEMBUNYIKAN]${m.groupValues[2]}${m.groupValues[3]}"
        }
        out = CVV_PIN_RE.replace(out) { m ->
            "${m.groupValues[1]}: [RAHASIA-DISEMBUNYIKAN]"
        }
        out = PASSWORD_BEARER_RE.replace(out) { m ->
            "${m.groupValues[1]}: [SANDI-DISEMBUNYIKAN]"
        }
        out = BEARER_TOKEN_RE.replace(out) { m ->
            "${m.groupValues[1]}[TOKEN-DISEMBUNYIKAN]"
        }
        out = JSON_TOKEN_RE.replace(out) { m ->
            "${m.groupValues[1]}[TOKEN-DISEMBUNYIKAN]${m.groupValues[3]}"
        }
        out = API_TOKEN_SECRET_RE.replace(out) {
            "[TOKEN-DISEMBUNYIKAN]"
        }
        out = CARD_CANDIDATE_RE.replace(out) { m ->
            val raw = m.groupValues[1]
            val digits = raw.filter { it.isDigit() }
            if (passesLuhn(digits)) {
                "${digits.take(4)}-****-****-${digits.takeLast(4)}"
            } else {
                raw
            }
        }
        return out
    }

    /**
     * Netralkan semua tag protokol internal di dalam teks tak tepercaya agar tidak dapat menutup
     * `<tool_result>` atau `<system-reminder>` lebih awal maupun menyuntikkan `<tool_use>` palsu.
     */
    fun escapeProtocolTags(text: String): String {
        if (!text.contains('<')) return text
        return text
            .replace("</tool_result>", "<\\/tool_result>", ignoreCase = true)
            .replace("<tool_result", "<\\tool_result", ignoreCase = true)
            .replace("</tool_use>", "<\\/tool_use>", ignoreCase = true)
            .replace("<tool_use>", "<\\tool_use>", ignoreCase = true)
            .replace("</system-reminder>", "<\\/system-reminder>", ignoreCase = true)
            .replace("<system-reminder>", "<\\system-reminder>", ignoreCase = true)
    }

    /**
     * Bungkus dan bersihkan konten yang berasal dari sumber eksternal (Web, Email, Issue GitHub, Dokumen)
     * agar model tidak memperlakukan isi di dalamnya sebagai perintah sistem.
     */
    fun sanitizeExternalContent(text: String): String {
        return redactSensitiveCredentials(escapeProtocolTags(text))
    }
}
