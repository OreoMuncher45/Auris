package com.music.bitchord.playback.audio

/**
 * The loop mix's shifts on [PrecisionAudioSink]'s delegate clock, applied to
 * position reports when they are *heard* rather than when they are written.
 *
 * A drop or an inserted silence changes what the delegate's timeline is ahead
 * of the track's by at the frame it was written, but the delegate's position
 * only reaches that frame once the buffered audio ahead of it has played out.
 * Taking the new offset off straight away moved the reported track position
 * by the whole shift a buffer early, and the alignment's next reading with it.
 *
 * Each shift is queued at its delegate-timeline boundary; [trackPositionUs]
 * retires the ones the delegate position has passed. Through inserted silence
 * the track position holds at the frame the silence was written before.
 *
 * Fixed-size and allocation-free; read and written on the playback thread only.
 */
internal class ShiftTimeline(private val capacity: Int = 32) {
    private val starts = LongArray(capacity)
    private val before = LongArray(capacity)
    private val deltas = LongArray(capacity)
    private var head = 0
    private var count = 0

    /** The offset for frames being written now: the delegate's timeline minus the track's. */
    var writtenUs = 0L
        private set

    /** The offset for the frame being heard, as of the last [trackPositionUs]. */
    private var heardUs = 0L

    /** Frames dropped from the track, the next written frame landing at [boundaryUs] on the delegate's timeline. */
    fun drop(boundaryUs: Long, durationUs: Long) = record(boundaryUs, -durationUs)

    /** Silence written to the delegate from [boundaryUs], covering no track time. */
    fun insertSilence(boundaryUs: Long, durationUs: Long) = record(boundaryUs, durationUs)

    private fun record(boundaryUs: Long, deltaUs: Long) {
        if (deltaUs == 0L) return
        if (count > 0) {
            val last = (head + count - 1) % capacity
            val lastDelta = deltas[last]
            // A drop spread over several input buffers lands at one boundary; a
            // run of silence blocks is contiguous. Either way, one shift.
            val merges = if (deltaUs < 0) {
                lastDelta < 0 && starts[last] == boundaryUs
            } else {
                lastDelta > 0 && starts[last] + lastDelta == boundaryUs
            }
            if (merges) {
                deltas[last] = lastDelta + deltaUs
                writtenUs += deltaUs
                return
            }
        }
        if (count == capacity) {
            // Out of room: the oldest shift is taken as heard, a little early.
            heardUs = before[head] + deltas[head]
            head = (head + 1) % capacity
            count--
        }
        val slot = (head + count) % capacity
        starts[slot] = boundaryUs
        before[slot] = writtenUs
        deltas[slot] = deltaUs
        count++
        writtenUs += deltaUs
    }

    /** The track position for [delegatePositionUs], on the delegate's timeline. */
    fun trackPositionUs(delegatePositionUs: Long): Long {
        while (count > 0 && delegatePositionUs >= starts[head] + maxOf(deltas[head], 0L)) {
            heardUs = before[head] + deltas[head]
            head = (head + 1) % capacity
            count--
        }
        // Inside inserted silence: no track time is passing.
        if (count > 0 && delegatePositionUs >= starts[head]) return starts[head] - before[head]
        return delegatePositionUs - heardUs
    }

    fun reset() {
        head = 0
        count = 0
        writtenUs = 0L
        heardUs = 0L
    }
}
