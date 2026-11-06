package com.music.bitchord.data.sing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rewrite that frees the models' time axis.
 *
 * The models are published with their time dimension pinned at 256 frames,
 * and a graph that says 256 will only accept 256 -- so the separator, which
 * asks for 32, gets nothing at all and the song plays unseparated while
 * the counters say everything is fine.
 *
 * The rewrite has been wrong once, and wrong in the quietest way there is:
 * it looked for the frame count one level too high in the protobuf, found
 * nothing, and returned "no change" without saying so. The file was copied
 * into place unchanged, the model still asked for 256 frames, and nothing
 * about the music was different.
 *
 * So the bytes here are the real model's own bytes -- the input and output
 * descriptions lifted out of the published file, byte for byte -- and the
 * rewrite is measured against them rather than against a shape somebody
 * drew on paper.
 */
class OnnxFramePatchTest {

    /** The bytes a rewritten dimension is made of: `dim_param = "f"`. */
    private val replacement = byteArrayOf(0x12, 0x01, 'f'.code.toByte())

    /** The frozen time dimension: `dim_value = 256`, three bytes on the wire. */
    private val frozen = byteArrayOf(0x08, 0x80.toByte(), 0x02)

    /** The bin dimension: `dim_value = 2048`, which must not be touched. */
    private val bins = byteArrayOf(0x08, 0x80.toByte(), 0x10)

    /**
     * The published model's input description, byte for byte.
     *
     * `input`, a float tensor of shape `[batch_size, 4, 2048, 256]`, where
     * the last axis is the time axis and the only one that has to change.
     */
    private val realInput = hex(
        "0a05696e70757412220a200801121c0a0c120a62617463685f73697a65" +
            "0a0208040a030880100a03088002",
    )

    /** The same model, on the way out. */
    private val realOutput = hex(
        "0a066f757470757412220a200801121c0a0c120a62617463685f73697a65" +
            "0a0208040a030880100a03088002",
    )

    @Test
    fun `the time dimension is found and made symbolic`() {
        val model = modelWith(realInput, realOutput)
        val patched = OnnxFramePatch.makeFramesDynamic(model)

        assertNotNull("the rewrite found nothing to change", patched)
        assertEquals(
            "the rewrite changed the length of the file",
            model.size,
            patched!!.size,
        )
        assertTrue(
            "the dimension was not made symbolic",
            contains(patched, replacement),
        )
        assertFalse(
            "the frozen frame count is still in there",
            contains(patched, frozen),
        )
    }

    @Test
    fun `both the input and the output are rewritten`() {
        val model = modelWith(realInput, realOutput)
        val patched = OnnxFramePatch.makeFramesDynamic(model)!!

        // Once for the input's shape and once for the output's.
        assertEquals(2, countOf(patched, replacement))
    }

    @Test
    fun `the other dimensions are left alone`() {
        // Only the time axis is rewritten. The bin count, the channel
        // count, the element type and the batch axis all stay as they
        // were: a model with a wrong bin count is a model that separates
        // the wrong thing.
        val model = modelWith(realInput, realOutput)
        val patched = OnnxFramePatch.makeFramesDynamic(model)!!

        assertTrue("the bin dimension was changed", contains(patched, bins))
        assertTrue(
            "the batch axis was changed",
            contains(patched, hex("120a62617463685f73697a65")),
        )
        assertTrue(
            "the element type was changed",
            contains(patched, hex("0801")),
        )
    }

    @Test
    fun `a model that does not have the published frame count is left alone`() {
        // Nothing to do means nothing to change: the file is returned as
        // "not mine to touch" rather than half-rewritten.
        val model = modelWith(
            replace(realInput, frozen, hex("088004")),
            replace(realOutput, frozen, hex("088004")),
        )
        assertNull(OnnxFramePatch.makeFramesDynamic(model))
    }

    @Test
    fun `a model that is already symbolic is left alone`() {
        val model = modelWith(
            replace(realInput, frozen, replacement),
            replace(realOutput, frozen, replacement),
        )
        assertNull(OnnxFramePatch.makeFramesDynamic(model))
    }

    @Test
    fun `a file that is not a model is left alone`() {
        assertNull(OnnxFramePatch.makeFramesDynamic(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    // ---- the bytes ---------------------------------------------------------

    /**
     * A model whose graph holds the given input and output descriptions.
     *
     * The rest of the graph is not needed by the rewrite -- it walks the
     * graph's own input and output fields and nothing else -- so the model
     * is the graph and those two fields.
     */
    private fun modelWith(input: ByteArray, output: ByteArray): ByteArray =
        messageField(7, messageField(11, input) + messageField(12, output))

    private fun varint(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var rest = value
        do {
            var byte = (rest and 0x7FL).toInt()
            rest = rest ushr 7
            if (rest != 0L) byte = byte or 0x80
            out.add(byte.toByte())
        } while (rest != 0L)
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int): ByteArray =
        varint((field.toLong() shl 3) or wire.toLong())

    private fun messageField(field: Int, payload: ByteArray): ByteArray =
        tag(field, 2) + varint(payload.size.toLong()) + payload

    private fun hex(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        val out = ByteArray(clean.length / 2)
        for (index in out.indices) {
            out[index] = clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun replace(haystack: ByteArray, needle: ByteArray, with: ByteArray): ByteArray {
        require(needle.size == with.size) { "the replacement must keep the length" }
        val out = haystack.copyOf()
        var at = 0
        while (at + needle.size <= out.size) {
            var matched = true
            for (index in needle.indices) {
                if (out[at + index] != needle[index]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                System.arraycopy(with, 0, out, at, with.size)
                return out
            }
            at++
        }
        return out
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        countOf(haystack, needle) > 0

    private fun countOf(haystack: ByteArray, needle: ByteArray): Int {
        var count = 0
        var at = 0
        while (at + needle.size <= haystack.size) {
            var matched = true
            for (index in needle.indices) {
                if (haystack[at + index] != needle[index]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                count++
                at += needle.size
            } else {
                at++
            }
        }
        return count
    }
}
