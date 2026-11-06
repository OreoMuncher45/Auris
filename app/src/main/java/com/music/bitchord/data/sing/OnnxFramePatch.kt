package com.music.bitchord.data.sing

/**
 * Turns the MDX-Net models' frozen time dimension into a symbolic one.
 *
 * The models ship with the time axis pinned at 256 frames -- 5.94 seconds of
 * audio per inference. The network underneath is fully convolutional in time
 * and separates just as well from a shorter window (measured against a
 * reference mixture: correlation 0.9978 at 64 frames against 0.9979 at 256),
 * but the graph does not say so, and a graph that says 256 will only accept
 * 256.
 *
 * So the dimension is rewritten. It is done by editing the protobuf bytes
 * rather than by rebuilding the graph, because the app has to do this on a
 * phone with no ONNX tooling: the file is a flat sequence of length-prefixed
 * fields, and the only thing that has to change is one field in each of the
 * input and output descriptions.
 *
 * The rewrite is deliberately length-preserving. `dim_value = 256` occupies
 * three bytes on the wire -- a tag, then a two-byte varint -- and a
 * one-character `dim_param` occupies three bytes too: a tag, a length, and
 * the character. Replacing one with the other leaves every enclosing length
 * prefix correct, so the rest of the file does not have to be touched and
 * the result is byte-for-byte the same length as the input.
 *
 * Field numbers, from `onnx.proto`:
 *
 *   ModelProto.graph      = 7      GraphProto.input    = 11
 *   GraphProto.output     = 12     ValueInfoProto.type = 2
 *   TypeProto.tensor_type = 1      Tensor.shape        = 2
 *   TensorShapeProto.dim  = 1      Dimension.dim_value = 1
 *   Dimension.dim_param   = 2
 *
 * The shape's dimensions are *messages*, not numbers: `dim` holds either
 * the size of the axis or its name, and only one of the two at a time. The
 * size is what has to change, so the walk goes
 *
 *   shape -> dim (a message) -> dim_value (the number)
 *
 * and stops at the first level. Stopping one level short is what this
 * patch used to do, and it is why it never matched anything: the shape's
 * own field 1 is the `dim` message, not the `dim_value` number inside it,
 * so every dimension was skipped and the rewrite quietly did nothing --
 * and the model on the phone was left asking for 256 frames while the
 * separator asked it for 32.
 */
object OnnxFramePatch {

    /** The dimension the models are published with. */
    const val PUBLISHED_FRAMES = 256

    /**
     * Rewrites the model's time dimension so any frame count is accepted.
     *
     * Returns null when the file does not look like the model this was
     * written for -- a graph with no such dimension, or one whose dimension
     * is not the length-preserving size. A null means "use the model as it
     * is", which still works, just at 256 frames and with five times the
     * latency.
     */
    fun makeFramesDynamic(bytes: ByteArray, frames: Int = PUBLISHED_FRAMES): ByteArray? {
        val graph = fields(bytes, 0, bytes.size)
            .firstOrNull { it.number == GRAPH && it.wireType == WIRE_LENGTH }
            ?: return null

        val edits = ArrayList<IntRange>()
        for (field in fields(bytes, graph.valueStart, graph.valueEnd)) {
            if (field.number != GRAPH_INPUT && field.number != GRAPH_OUTPUT) continue
            if (field.wireType != WIRE_LENGTH) continue
            for (dimension in dimValueFields(bytes, field.valueStart, field.valueEnd)) {
                if (readVarint(bytes, dimension.valueStart).first != frames.toLong()) continue
                val width = dimension.valueEnd - dimension.fieldStart
                if (width != REPLACEMENT.size) return null
                edits += dimension.fieldStart until dimension.valueEnd
            }
        }
        if (edits.isEmpty()) return null

        val out = ByteArray(bytes.size)
        var source = 0
        var target = 0
        for (edit in edits.sortedBy { it.first }) {
            System.arraycopy(bytes, source, out, target, edit.first - source)
            target += edit.first - source
            System.arraycopy(REPLACEMENT, 0, out, target, REPLACEMENT.size)
            target += REPLACEMENT.size
            source = edit.last + 1
        }
        System.arraycopy(bytes, source, out, target, bytes.size - source)
        return out
    }

    /** One field of a protobuf message. */
    private data class Field(
        val number: Int,
        val wireType: Int,
        /** Where the field's tag starts. */
        val fieldStart: Int,
        /** Where the field's value starts, after the tag and any length. */
        val valueStart: Int,
        /** Where the field's value ends. */
        val valueEnd: Int,
    )

    /**
     * Lists the fields of one protobuf message.
     *
     * Length-delimited fields are skipped whole, so scanning a 30 MB model
     * costs one step per top-level field rather than one per byte.
     */
    private fun fields(data: ByteArray, start: Int, end: Int): List<Field> {
        val found = ArrayList<Field>()
        var position = start
        while (position < end) {
            val fieldStart = position
            val tag = readVarint(data, position)
            position = tag.second
            val number = (tag.first ushr 3).toInt()
            val wireType = (tag.first and 7L).toInt()
            when (wireType) {
                WIRE_VARINT -> {
                    val valueStart = position
                    position = readVarint(data, position).second
                    found += Field(number, wireType, fieldStart, valueStart, position)
                }
                WIRE_64BIT -> {
                    val valueStart = position
                    position += 8
                    found += Field(number, wireType, fieldStart, valueStart, position)
                }
                WIRE_LENGTH -> {
                    val length = readVarint(data, position)
                    position = length.second
                    found += Field(number, wireType, fieldStart, position, position + length.first.toInt())
                    position += length.first.toInt()
                }
                WIRE_32BIT -> {
                    val valueStart = position
                    position += 4
                    found += Field(number, wireType, fieldStart, valueStart, position)
                }
                else -> return found
            }
        }
        return found
    }

    /**
     * The `dim_value` fields inside one ValueInfoProto.
     *
     * The path down is fixed by the proto: type, then tensor_type, then
     * shape, then each dimension, and inside each dimension the number.
     */
    private fun dimValueFields(data: ByteArray, start: Int, end: Int): List<Field> {
        val found = ArrayList<Field>()
        for (type in fields(data, start, end)) {
            if (type.number != VALUE_TYPE || type.wireType != WIRE_LENGTH) continue
            for (tensor in fields(data, type.valueStart, type.valueEnd)) {
                if (tensor.number != TENSOR_TYPE || tensor.wireType != WIRE_LENGTH) continue
                for (shape in fields(data, tensor.valueStart, tensor.valueEnd)) {
                    if (shape.number != TENSOR_SHAPE || shape.wireType != WIRE_LENGTH) continue
                    for (dimension in fields(data, shape.valueStart, shape.valueEnd)) {
                        if (dimension.number != DIMENSION || dimension.wireType != WIRE_LENGTH) continue
                        for (value in fields(data, dimension.valueStart, dimension.valueEnd)) {
                            if (value.number != DIM_VALUE || value.wireType != WIRE_VARINT) continue
                            found += value
                        }
                    }
                }
            }
        }
        return found
    }

    /** Reads a base-128 varint, returning its value and the position after it. */
    private fun readVarint(data: ByteArray, position: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var index = position
        while (true) {
            val byte = data[index].toInt() and 0xFF
            index++
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result to index
            shift += 7
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_64BIT = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_32BIT = 5

    private const val GRAPH = 7
    private const val GRAPH_INPUT = 11
    private const val GRAPH_OUTPUT = 12
    private const val VALUE_TYPE = 2
    private const val TENSOR_TYPE = 1
    private const val TENSOR_SHAPE = 2

    /** The shape's dimension messages: one per axis. */
    private const val DIMENSION = 1

    /** The number inside a dimension, as opposed to its name. */
    private const val DIM_VALUE = 1

    /**
     * `dim_param = "f"`: field 2, length 1, one character. Three bytes, the
     * same as the `dim_value = 256` it replaces.
     */
    private val REPLACEMENT = byteArrayOf(0x12, 0x01, 'f'.code.toByte())
}
