package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

/** DNS rebinding: pemeriksaan harus terjadi pada alamat hasil DNS yang dipakai connect, bukan hanya sebelumnya. */
class PublicOnlyDnsTest {
    private fun ip(vararg b: Int) = InetAddress.getByAddress(ByteArray(4) { b[it].toByte() })

    private fun blocked(addrs: List<InetAddress>, host: String = "contoh.example.com"): Boolean = try {
        PublicOnlyDns { addrs }.lookup(host)
        false
    } catch (e: BlockedAddressException) {
        true
    }

    @Test
    fun allowsPublicAddresses() {
        val a = listOf(ip(93, 184, 216, 34))
        assertEquals(a, PublicOnlyDns { a }.lookup("contoh.example.com"))
    }

    @Test
    fun rejectsLoopbackPrivateAndMetadata() {
        for (a in listOf(ip(127, 0, 0, 1), ip(10, 0, 0, 5), ip(192, 168, 1, 1), ip(169, 254, 169, 254), ip(100, 64, 0, 1))) {
            if (!blocked(listOf(a))) fail("seharusnya ditolak: $a")
        }
    }

    @Test
    fun rejectsWhenAnyAddressIsPrivate() {
        if (!blocked(listOf(ip(93, 184, 216, 34), ip(127, 0, 0, 1)))) fail("campuran publik+privat harus ditolak")
    }

    @Test
    fun rejectsLocalHostNamesWithoutLookup() {
        if (!blocked(listOf(ip(93, 184, 216, 34)), host = "localhost")) fail("localhost harus ditolak")
    }
}
