package com.mythos.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Menguji enkripsi API key di Android Keystore sungguhan. Tidak bisa berjalan di JVM biasa.
 * Tes ini tidak menyentuh SharedPreferences aplikasi, jadi aman dijalankan di perangkat yang berisi data asli.
 */
@RunWith(AndroidJUnit4::class)
class SecretBoxInstrumentedTest {

    @Test
    fun enkripsiLalu_dekripsi_mengembalikanTeksAsli() {
        val kunci = "sk-or-v1-0123456789abcdef"
        assertEquals(kunci, SecretBox.decrypt(SecretBox.encrypt(kunci)))
    }

    @Test
    fun teksKosongDanUnicode_tetapUtuh() {
        assertEquals("", SecretBox.decrypt(SecretBox.encrypt("")))
        val unik = "kunci-é-漢字-🔑"
        assertEquals(unik, SecretBox.decrypt(SecretBox.encrypt(unik)))
    }

    @Test
    fun enamBelasKunci_semuaTerbacaUtuh() {
        val asli = (1..16).map { "sk-or-v1-kunci-$it-${"x".repeat(it)}" }
        val terenkripsi = asli.map { SecretBox.encrypt(it) }
        assertEquals(asli, terenkripsi.map { SecretBox.decrypt(it) })
    }

    @Test
    fun teksSamaDienkripsiDuaKali_menghasilkanCiphertextBerbeda() {
        assertNotEquals(SecretBox.encrypt("abc"), SecretBox.encrypt("abc"))
    }

    @Test
    fun masukanRusak_mengembalikanNullBukanCrash() {
        assertNull(SecretBox.decrypt("bukan-base64-yang-valid!!!"))
        assertNull(SecretBox.decrypt(Base64.encodeToString(ByteArray(5), Base64.NO_WRAP)))
    }

    @Test
    fun ciphertextDiubah_ditolakOlehGcm() {
        val bytes = Base64.decode(SecretBox.encrypt("rahasia"), Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        assertNull(SecretBox.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }
}
