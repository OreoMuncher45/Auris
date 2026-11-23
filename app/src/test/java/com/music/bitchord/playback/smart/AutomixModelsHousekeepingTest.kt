package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutomixModelsHousekeepingTest {

    @Test
    fun `no failure means no cooldown`() {
        assertEquals(0L, AutomixModels.cooldownLeft(failedAt = 0L, now = 5_000L))
    }

    @Test
    fun `a failure holds back fetches for two minutes, then the retry may run`() {
        assertEquals(120_000L, AutomixModels.cooldownLeft(failedAt = 1_000L, now = 1_000L))
        assertEquals(30_000L, AutomixModels.cooldownLeft(failedAt = 1_000L, now = 91_000L))
        assertEquals(0L, AutomixModels.cooldownLeft(failedAt = 1_000L, now = 121_000L))
        assertEquals(0L, AutomixModels.cooldownLeft(failedAt = 1_000L, now = 500_000L))
    }

    @Test
    fun `delete reports a file that could not be removed, and counts absent ones as removed`() {
        val dir = Files.createTempDirectory("automix-models").toFile()
        try {
            val removable = File(dir, "beat.onnx").apply { writeText("x") }
            val absent = File(dir, "vocals.onnx.part")
            // A non-empty directory under a model's name: File.delete() refuses it.
            val stuck = File(dir, "vocals.onnx").apply { mkdir(); File(this, "inner").writeText("x") }

            val left = AutomixModels.deleteAll(listOf(removable, absent, stuck))

            assertEquals(listOf(stuck), left)
            assertEquals(false, removable.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
