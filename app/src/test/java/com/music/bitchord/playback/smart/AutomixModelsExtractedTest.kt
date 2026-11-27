package com.music.bitchord.playback.smart

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixModelsExtractedTest {

    private val dir = Files.createTempDirectory("models").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun extracted(bytes: Int, digest: String?): File {
        val file = File(dir, "beat.onnx").apply { writeBytes(ByteArray(bytes)) }
        if (digest != null) File(dir, "beat.onnx.sha256").writeText(digest)
        return file
    }

    @Test
    fun theCopyUnpackedFromTheseWeightsIsReused() {
        val file = extracted(16, "aaaa")
        assertTrue(AutomixModels.extractedIsCurrent(file, AutomixModels.Model("beat.onnx", 16, "aaaa")))
    }

    @Test
    fun newWeightsOfTheSameSizeAreUnpackedAgain() {
        // What a size-only check kept: the old weights, same length.
        val file = extracted(16, "aaaa")
        assertFalse(AutomixModels.extractedIsCurrent(file, AutomixModels.Model("beat.onnx", 16, "bbbb")))
    }

    @Test
    fun aCopyWithNoRecordedDigestIsUnpackedAgain() {
        // Unpacked by a build before the digest was recorded, or cut off before
        // it was written.
        val file = extracted(16, null)
        assertFalse(AutomixModels.extractedIsCurrent(file, AutomixModels.Model("beat.onnx", 16, "aaaa")))
    }

    @Test
    fun aCopyOfTheWrongSizeIsUnpackedAgain() {
        val file = extracted(8, "aaaa")
        assertFalse(AutomixModels.extractedIsCurrent(file, AutomixModels.Model("beat.onnx", 16, "aaaa")))
    }
}
