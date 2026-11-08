package com.mythos.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldStartFollowingTest {

    @Test
    fun semuaSyaratTerpenuhi_mulaiMengikuti() {
        assertTrue(shouldStartFollowing(pinned = 7L, roomSeenFor = 7L, rawTail = 0, userDragged = false))
    }

    @Test
    fun tanpaPin_tidakMengikutiLewatAturanIni() {
        assertFalse(shouldStartFollowing(pinned = null, roomSeenFor = 7L, rawTail = 0, userDragged = false))
    }

    @Test
    fun ruangKosongBelumPernahTerlihat_tidakMengikuti() {
        assertFalse(shouldStartFollowing(pinned = 7L, roomSeenFor = null, rawTail = 0, userDragged = false))
    }

    @Test
    fun ruangKosongTercatatUntukPesanLain_tidakMengikuti() {
        assertFalse(shouldStartFollowing(pinned = 8L, roomSeenFor = 7L, rawTail = 0, userDragged = false))
    }

    @Test
    fun masihAdaRuangKosong_belumMengikuti() {
        assertFalse(shouldStartFollowing(pinned = 7L, roomSeenFor = 7L, rawTail = 120, userDragged = false))
    }

    @Test
    fun penggunaSedangMenggeser_tidakMengikuti() {
        assertFalse(shouldStartFollowing(pinned = 7L, roomSeenFor = 7L, rawTail = 0, userDragged = true))
    }
}
