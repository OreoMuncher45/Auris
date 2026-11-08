package com.music.bitchord.playback.smart

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * The listener's own choices for each track's transition, kept on disk.
 *
 * ## Why this exists
 *
 * Automix works out a transition from the two recordings: the tempi, the
 * structure, the vocals. That answer is right about the *music* and silent
 * about what the listener wants. A listener who wants every transition into
 * one particular song to be a long, filtered ride has to be able to say so
 * once and have it mean something every time that song plays — which means
 * the choice has to live with the song, not with the session.
 *
 * ## Shape
 *
 * One file per track, like [AnalysisStore]: a write can only damage its own
 * entry, and a prune is a file delete. The file holds the choice and nothing
 * else — no analysis, no cached plan — because a plan describes two tracks
 * and a choice describes one.
 */
class AutomixProfileStore(
    /** Where the profiles are kept, resolved lazily — see [AnalysisStore]. */
    private val directoryOf: () -> File,
) {
    private val directory by lazy { File(directoryOf(), DIRECTORY) }

    /** Track ids known to have no file, so a track without one does not hit the disk on every tick. */
    private val known = ConcurrentHashMap<String, Boolean>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The choice stored for [trackId], or null when there is none.
     *
     * A file that cannot be read is worth exactly nothing, so it is discarded
     * rather than returned half-filled: a choice that cannot be understood
     * would silently be the wrong choice.
     */
    fun load(trackId: String): AutomixOverride? {
        if (trackId.isBlank()) return null
        if (known[trackId] == false) return null
        val file = File(directory, fileNameFor(trackId))
        if (!file.exists()) {
            known[trackId] = false
            return null
        }
        return runCatching {
            val stored = json.decodeFromString(Stored.serializer(), file.readText())
            // Version 3 is read as well as written: the only thing it lacks is
            // the volume shape, and that has an exact default — "leave the
            // volume alone" — so a file written before the shape existed says
            // the same thing as one written now with nothing chosen. Reading it
            // keeps the listener's other choices, which is the whole point of
            // keeping a choice with the song.
            require(stored.version == SCHEMA_VERSION || stored.version == PREVIOUS_SCHEMA_VERSION) {
                "schema ${stored.version}"
            }
            stored.toOverride()
        }
            .onFailure {
                AnalysisLog.warn("Discarding unreadable Automix profile for $trackId", it)
                file.delete()
                known[trackId] = false
            }
            .getOrNull()
    }

    /**
     * Stores [override] for [trackId], or clears the entry when it says
     * nothing.
     *
     * A choice that says nothing is the same as no choice, so it is not
     * written: there is one way to say "Automix decides", and that is not
     * having a file.
     */
    fun save(trackId: String, override: AutomixOverride) {
        if (trackId.isBlank()) return
        if (override.isEmpty) {
            clear(trackId)
            return
        }
        runCatching {
            directory.mkdirs()
            val file = File(directory, fileNameFor(trackId))
            // Written aside and moved into place, so a kill mid-write leaves
            // the old choice rather than a truncated one.
            val temporary = File(directory, file.name + ".tmp")
            try {
                temporary.writeText(json.encodeToString(Stored.serializer(), Stored.of(override)))
                moveInto(temporary, file)
            } finally {
                temporary.delete()
            }
            known[trackId] = true
        }.onFailure { AnalysisLog.warn("Could not store Automix profile for $trackId", it) }
    }

    /** Forgets [trackId]'s choice. */
    fun clear(trackId: String) {
        if (trackId.isBlank()) return
        File(directory, fileNameFor(trackId)).delete()
        known[trackId] = false
    }

    private fun moveInto(temporary: File, file: File) {
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Hashed rather than used raw: a track id is not guaranteed to be a legal filename. */
    private fun fileNameFor(trackId: String): String = "${trackId.hashCode().toUInt()}_${trackId.length}.json"

    /**
     * The persisted subset, kept apart from [AutomixOverride] so that adding a
     * field to the in-memory type is not silently a schema change.
     */
    @Serializable
    private data class Stored(
        val version: Int = SCHEMA_VERSION,
        val enabled: Boolean = true,
        val style: String = "",
        val bars: Int = 0,
        val filter: String = "",
        val eq: String = "",
        val effects: String = "",
        val volume: String = "",
        val transitionStart: Double = 0.0,
        val transitionEnd: Double = 0.0,
    ) {
        fun toOverride(): AutomixOverride = AutomixOverride(
            enabled = enabled,
            style = style.takeIf { it.isNotBlank() }?.let { name ->
                TransitionStyle.entries.firstOrNull { it.name == name }
            },
            bars = bars.coerceIn(0, MAX_BARS),
            filter = filter.takeIf { it.isNotBlank() }?.let { name ->
                FilterTransitionVariant.entries.firstOrNull { it.name == name }
            },
            eq = eq.takeIf { it.isNotBlank() }?.let { name ->
                TransitionEqShape.entries.firstOrNull { it.name == name }
            },
            effects = effects.takeIf { it.isNotBlank() }?.let { name ->
                AutomixEffect.entries.firstOrNull { it.name == name }
            },
            volume = volume.takeIf { it.isNotBlank() }?.let { name ->
                TransitionVolumeShape.entries.firstOrNull { it.name == name }
            },
            transitionStart = transitionStart.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
            transitionEnd = transitionEnd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
        )

        companion object {
            fun of(override: AutomixOverride) = Stored(
                enabled = override.enabled,
                style = override.style?.name.orEmpty(),
                bars = override.bars.coerceIn(0, MAX_BARS),
                filter = override.filter?.name.orEmpty(),
                eq = override.eq?.name.orEmpty(),
                effects = override.effects?.name.orEmpty(),
                volume = override.volume?.name.orEmpty(),
                transitionStart = override.transitionStart.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                transitionEnd = override.transitionEnd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
            )
        }
    }

    companion object {
        const val DIRECTORY = "automix-profiles"

        /**
         * Version 4 added the volume shape and the second edge of the panel's
         * box. Version 3 added the effect choice. Version 2 added the tone
         * shape and the listener's own starting point for the overlap. Version
         * 1 files are discarded rather than read as half a choice: an old file
         * is a choice without the parts it did not have, and guessing at them
         * would be worse than starting again.
         *
         * Version 3 is read rather than discarded, because the two things it
         * lacks both have exact defaults — see [load].
         */
        const val SCHEMA_VERSION = 4

        /** The schema before the volume shape, read as well as written. */
        const val PREVIOUS_SCHEMA_VERSION = 3

        /** The musical range the planner works in; a stored choice outside it is clamped. */
        const val MAX_BARS = 32
    }
}
