package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceTest {
    @Test
    fun speedIsCalmAtRestAndCatchesUpWhenBacklogGrows() {
        assertEquals(REVEAL_CPS, revealSpeed(0), 0.01f)
        assertTrue(revealSpeed(2000) > revealSpeed(100))
        assertTrue(revealSpeed(100_000) <= REVEAL_CPS * 2.5f + 0.01f)
    }

    @Test
    fun holdTimesFollowBlockKind() {
        assertEquals(0L, paceHoldMs(UserBlock(UserItem(1, "q"))))
        assertEquals(STEPS_HOLD_MS, paceHoldMs(StepsBlock(emptyList())))
        val short = paceHoldMs(TextBlock(ThoughtItem(2, "Halo.")))
        val long = paceHoldMs(TextBlock(ThoughtItem(3, "x".repeat(200))))
        assertTrue(long > short)
        assertTrue(short >= TEXT_PAUSE_MS)
    }

    @Test
    fun revealDurationMatchesReferenceRecording() {
        // rekaman: 86 huruf ±0,7 dtk, 190 huruf ±1,0 dtk
        assertTrue(revealDurationMs(86) in 400L..900L)
        assertTrue(revealDurationMs(190) in 800L..1500L)
        assertEquals(0L, revealDurationMs(0))
        assertTrue(revealDurationMs(1200) > revealDurationMs(600))
    }

    @Test
    fun gapsBetweenBlocksStayShort() {
        assertTrue(TEXT_PAUSE_MS <= 400L)
        assertTrue(TAIL_HOLD_MS <= 400L)
    }

    @Test
    fun nextBlockAppearsRightAfterTypingFinishes() {
        val text = TextBlock(ThoughtItem(1, "x".repeat(100)))
        // ketikan belum selesai -> belum boleh, berapa lama pun (di bawah batas pengaman)
        assertTrue(!releaseReady(text, nowMs = 2_000, releasedAtMs = 0, doneAtMs = null))
        // selesai di 1000 -> boleh tepat setelah TEXT_PAUSE_MS
        assertTrue(!releaseReady(text, 1_000 + TEXT_PAUSE_MS - 1, 0, 1_000))
        assertTrue(releaseReady(text, 1_000 + TEXT_PAUSE_MS, 0, 1_000))
        // tak pernah melapor selesai -> batas pengaman
        assertTrue(releaseReady(text, paceHoldMs(text) + RELEASE_SAFETY_MS + 1, 0, null))
        // kelompok langkah: lama tampil minimal
        val steps = StepsBlock(emptyList())
        assertTrue(!releaseReady(steps, STEPS_HOLD_MS - 1, 0, null))
        assertTrue(releaseReady(steps, STEPS_HOLD_MS, 0, null))
    }
}
