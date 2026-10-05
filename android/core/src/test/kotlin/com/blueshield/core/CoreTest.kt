package com.blueshield.core

import com.blueshield.core.gender.GenderEstimate
import com.blueshield.core.gender.Override
import com.blueshield.core.gender.censorDecision
import com.blueshield.core.image.Box
import com.blueshield.core.image.ByteMask
import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.MaskOps
import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.PersonDetector
import com.blueshield.core.pipeline.Composer
import com.blueshield.core.pipeline.FrameRecord
import com.blueshield.core.pipeline.MaskStore
import com.blueshield.core.track.Detection
import com.blueshield.core.track.OpticalFlow
import com.blueshield.core.track.RegionTracker
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {
    private val spec = PipelineSpec.bundled

    @Test fun specMatchesSettingsDefaults() {
        assertEquals(CensorSettings(), spec.defaults)
        assertEquals(setOf("quality", "balanced", "fast"), spec.speedPresets.keys)
        assertEquals("female", spec.defaults.target)
    }

    @Test fun colorParsing() {
        assertEquals(0x1E4DFF, CensorSettings.parseColor("#1E4DFF"))
        assertEquals(0x0000FF, CensorSettings.parseColor("00f"))
    }

    @Test fun genderNeedsVotesAndConfidence() {
        fun est() = GenderEstimate(spec.gender.voteFactor, spec.gender.maxLogit, spec.gender.minVotes)
        val g = est()
        assertEquals(GenderEstimate.Label.UNCERTAIN, g.label(0.7))
        repeat(2) { g.add(0.1f) }
        assertEquals(GenderEstimate.Label.UNCERTAIN, g.label(0.7))
        g.add(0.1f)
        assertEquals(GenderEstimate.Label.FEMALE, g.label(0.7))
        val m = est().apply { repeat(5) { add(0.95f) } }
        assertEquals(GenderEstimate.Label.MALE, m.label(0.7))
        val mixed = est().apply { listOf(0.2f, 0.8f, 0.3f, 0.7f).forEach { add(it) } }
        assertEquals(GenderEstimate.Label.UNCERTAIN, mixed.label(0.7))
        // one vote = factor * ln(0.8/0.2), identical to the desktop maths
        val one = est().apply { add(0.2f) }
        assertTrue(abs(one.logit - spec.gender.voteFactor * kotlin.math.ln(4.0)) < 1e-6)
    }

    @Test fun decisionsPolicyAndOverrides() {
        val F = GenderEstimate.Label.FEMALE
        val M = GenderEstimate.Label.MALE
        val U = GenderEstimate.Label.UNCERTAIN
        assertTrue(censorDecision(F, "female", "censor"))
        assertFalse(censorDecision(M, "female", "censor"))
        assertTrue(censorDecision(U, "female", "censor"))
        assertFalse(censorDecision(U, "female", "keep"))
        assertTrue(censorDecision(M, "everyone", "keep"))
        assertTrue(censorDecision(M, "female", "keep", Override.CENSOR))
        assertFalse(censorDecision(F, "female", "censor", Override.KEEP))
    }

    private fun skinWith(vararg rects: IntArray): ByteMask {
        val m = ByteMask(200, 100)
        for ((x0, y0, x1, y1) in rects.map { it.toList() }) for (y in y0 until y1) for (x in x0 until x1) m[x, y] = 255
        return m
    }

    @Test fun composeCensorsOnlyTheOwner() {
        val skin = skinWith(intArrayOf(20, 40, 60, 60), intArrayOf(140, 40, 180, 60))
        val rec = FrameRecord(floatArrayOf(1f, 0.05f, 0.1f, 0.4f, 0.9f, 2f, 0.6f, 0.1f, 0.95f, 0.9f), floatArrayOf(2f, 0.75f, 0.3f, 0.85f, 0.5f, 1f))
        val m = Composer.compose(skin, rec, mapOf(1 to true, 2 to false), true, spec.ownershipBoxPad)
        assertEquals(255, m[40, 50]); assertEquals(0, m[160, 50])
        val m2 = Composer.compose(skin, rec, mapOf(1 to false, 2 to true), false, spec.ownershipBoxPad)
        assertEquals(0, m2[40, 50]); assertEquals(255, m2[160, 50]); assertTrue(m2[160, 40] > 0)
    }

    @Test fun outstretchedArmFollowsItsBody() {
        val skin = skinWith(intArrayOf(10, 45, 100, 50))
        val rec = FrameRecord(floatArrayOf(1f, 0f, 0.1f, 0.25f, 0.9f), FloatArray(0))
        assertFalse(Composer.compose(skin, rec, mapOf(1 to false), true, spec.ownershipBoxPad).any())
    }

    @Test fun tinyUnassignedBlobsAreIgnored() {
        val skin = skinWith(intArrayOf(150, 2, 154, 5), intArrayOf(20, 40, 60, 60))
        val rec = FrameRecord(floatArrayOf(1f, 0.05f, 0.1f, 0.4f, 0.9f), FloatArray(0))
        val m = Composer.compose(skin, rec, mapOf(1 to true), true, spec.ownershipBoxPad, spec.unassignedMinArea)
        assertEquals(255, m[40, 50]); assertEquals(0, m[152, 3])
        val big = skinWith(intArrayOf(120, 10, 160, 40))
        assertEquals(255, Composer.compose(big, FrameRecord.EMPTY, emptyMap(), true, 0.06f, spec.unassignedMinArea)[140, 20])
    }

    @Test fun unassignedSkinFollowsFallback() {
        val skin = ByteMask(20, 20, ByteArray(400) { -1 })
        assertEquals(400, Composer.compose(skin, FrameRecord.EMPTY, emptyMap(), true, 0.06f).countOn())
        assertFalse(Composer.compose(skin, FrameRecord.EMPTY, emptyMap(), false, 0.06f).any())
    }

    @Test fun connectedComponents() {
        val w = 10
        val on = BooleanArray(100)
        for (x in 0..2) on[x] = true // component A
        for (x in 6..8) on[5 * w + x] = true // component B
        on[11] = true // diagonal neighbour of A? (1,1) touches (0..2,0) -> part of A
        val (labels, count) = MaskOps.connectedComponents(on, w, 10)
        assertEquals(3, count)
        assertEquals(labels[0], labels[11])
        assertTrue(labels[56] != labels[0])
    }

    @Test fun featherGrowsAndSoftens() {
        val m = ByteMask(100, 100)
        for (y in 40 until 60) for (x in 40 until 60) m[x, y] = 255
        val f = Composer.feather(m, 1000, 1000, softness = 50, aggressive = false)
        assertEquals(255, f[50, 50])
        assertTrue(f[38, 50] in 1..254) // soft edge just outside the original
        assertEquals(0, f[5, 5])
    }

    @Test fun maskStoreRoundTrip() {
        val f = File.createTempFile("masks", ".bin")
        MaskStore(f, 64, 32).use { s ->
            val a = ByteMask(64, 32)
            val b = ByteMask(64, 32).also { for (y in 5 until 10) for (x in 5 until 10) it[x, y] = 255 }
            s.append(a); s.append(b)
            assertEquals(2, s.size)
            assertFalse(s[0].any())
            assertTrue(s[1].data.contentEquals(b.data))
            assertFalse(s[7].any())
        }
        assertFalse(f.exists())
    }

    @Test fun regionTrackerSurvivesShortMiss() {
        val t = RegionTracker(maxMisses = 2)
        t.update(listOf(Detection("BUTTOCKS_EXPOSED", 0.9f, Box(10f, 10f, 40f, 40f))))
        t.update(emptyList())
        assertEquals(1, t.tracks.size)
        t.update(emptyList()); t.update(emptyList())
        assertEquals(0, t.tracks.size)
    }

    @Test fun opticalFlowFollowsShift() {
        // smooth random texture (no periodic pattern, so the shift is unambiguous)
        val rnd = java.util.Random(7)
        val base = FloatMask(160, 72, FloatArray(160 * 72) { rnd.nextFloat() * 255f })
        var smooth = base
        repeat(3) { smooth = MaskOps.areaDownscale(smooth, smooth.width, smooth.height).let { b ->
            FloatMask(b.width, b.height, FloatArray(b.data.size) { i ->
                val x = i % b.width; val y = i / b.width
                var acc = 0f; var n = 0
                for (dy in -2..2) for (dx in -2..2) { val xx = x + dx; val yy = y + dy
                    if (xx in 0 until b.width && yy in 0 until b.height) { acc += b.data[yy * b.width + xx]; n++ } }
                acc / n
            })
        } }
        fun frame(shift: Int) = RgbImage(128, 72).also { img ->
            for (y in 0 until 72) for (x in 0 until 128) {
                val v = smooth[x + 16 - shift, y].toInt().coerceIn(0, 255)
                val o = (y * 128 + x) * 3
                img.data[o] = v.toByte(); img.data[o + 1] = v.toByte(); img.data[o + 2] = v.toByte()
            }
        }
        val flow = OpticalFlow(128, 72)
        flow.update(frame(0))
        flow.update(frame(3))
        val (dx, dy) = flow.boxShift(Box(30f, 20f, 100f, 50f), 128, 72)
        assertTrue(abs(dx - 3f) < 1.0f, "dx=$dx")
        assertTrue(abs(dy) < 1.0f, "dy=$dy")
        // a mask follows the motion
        val mask = FloatMask(128, 72).also { for (y in 30 until 40) for (x in 50 until 60) it[x, y] = 1f }
        val warped = flow.warp(mask)
        assertTrue(warped[56, 35] > 0.5f && warped[51, 35] < 0.5f)
    }

    @Test fun anchorsMatchModelOutputs() {
        assertEquals(19206 * 4, PersonDetector.buildAnchors().size)
    }
}
