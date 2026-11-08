package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotAnimationTest {
    @Test
    fun all41PosesHaveValidNonOverlappingRectangles() {
        assertEquals(41, MASCOT_POSES.size)
        for ((poseId, rects) in MASCOT_POSES.withIndex()) {
            assertTrue("Pose $poseId should not be empty", rects.isNotEmpty())
            val seen = HashSet<Pair<Int, Int>>()
            for (packed in rects) {
                val c = mascotRectColor(packed)
                val x = mascotRectX(packed)
                val y = mascotRectY(packed)
                val w = mascotRectW(packed)
                val h = mascotRectH(packed)
                assertTrue(c in 0..3)
                assertTrue(w > 0 && h > 0)
                assertTrue(x in 0..35)
                assertTrue(y in -16..19)
                for (yy in y until (y + h)) {
                    for (xx in x until (x + w)) {
                        assertTrue("Duplicate cell ($xx, $yy) in pose $poseId", seen.add(xx to yy))
                    }
                }
            }
        }
    }

    @Test
    fun timelineLoopsContinuouslyAcrossAllThreeRoutines() {
        assertTrue(MASCOT_CYCLE_MS > 8_000L)
        assertEquals(0, mascotPoseAt(0L))
        // Routine 1 (3D walk)
        val walkStart = MASCOT_ROUTINE_WALK.first().durationMs.toLong()
        assertEquals(1, mascotPoseAt(walkStart + 10L))
        // Routine 2 (Stick poke)
        val stickOffset = mascotRoutineActiveOffsetMs(1)
        assertEquals(13, mascotPoseAt(stickOffset + 10L))
        // Routine 3 (Jump)
        val jumpOffset = mascotRoutineActiveOffsetMs(2)
        assertEquals(32, mascotPoseAt(jumpOffset + 10L))
        // Full cycle wraps back to Pose 0
        assertEquals(0, mascotPoseAt(MASCOT_CYCLE_MS + 10L))
    }
}
