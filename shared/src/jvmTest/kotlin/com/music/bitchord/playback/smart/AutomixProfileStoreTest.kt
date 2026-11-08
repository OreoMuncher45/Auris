package com.music.bitchord.playback.smart

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a listener's own choice has to survive.
 *
 * The point of the profile is that it means the same thing next time the song
 * plays, which means every part of it has to come back: the blend, the size,
 * the filter, the tone shape, the effect and the place the handoff starts.
 * A choice that loses a part on the way to disk is a choice that quietly
 * becomes a different choice, and the listener never finds out.
 */
class AutomixProfileStoreTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("automix-profiles").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store() = AutomixProfileStore { root }

    @Test
    fun keepsEveryPartOfAChoice() {
        val choice = AutomixOverride(
            enabled = true,
            style = TransitionStyle.DJ_BLEND,
            bars = 32,
            filter = FilterTransitionVariant.BASS_HANDOFF,
            eq = TransitionEqShape.BRIGHT,
            effects = AutomixEffect.ECHO_TAIL,
            volume = TransitionVolumeShape.SLAM,
            transitionStart = 42.5,
            transitionEnd = 61.5,
        )
        store().save("track-1", choice)
        assertEquals(choice, store().load("track-1"))
    }

    @Test
    fun keepsAChoiceThatTurnsAutomixOff() {
        // Not the same as no choice: "never touch this song's transition" is
        // a decision, and it has to outlive the session like any other.
        val choice = AutomixOverride(enabled = false)
        store().save("track-2", choice)
        assertEquals(choice, store().load("track-2"))
    }

    @Test
    fun anEmptyChoiceIsNoChoice() {
        store().save("track-3", AutomixOverride())
        assertNull(store().load("track-3"))
    }

    @Test
    fun clampsTheSizeToTheMusicalRange() {
        val store = store()
        store.save("track-4", AutomixOverride(bars = 99))
        assertEquals(32, store.load("track-4")?.bars)

        store.save("track-5", AutomixOverride(bars = 3))
        assertEquals(3, store.load("track-5")?.bars)
    }

    @Test
    fun discardsAFileFromAnOlderSchema() {
        // A file written before the effect existed is a choice without the
        // part it did not have. Reading it as a whole choice would be
        // guessing; throwing it away starts again from something true.
        val directory = File(root, AutomixProfileStore.DIRECTORY)
        directory.mkdirs()
        val file = File(directory, fileNameFor("track-6"))
        file.writeText("""{"version":2,"enabled":true,"style":"DJ_BLEND","bars":8,"filter":"","eq":"","transitionStart":0.0}""")

        assertNull(store().load("track-6"))
        assertTrue(!file.exists(), "an unreadable profile should be discarded, not kept")
    }

    @Test
    fun readsAFileFromTheVersionBeforeTheVolumeShape() {
        // The two things version 3 lacks both have an exact default — "leave
        // the volume alone", and "no second edge" — so the file says the same
        // thing as one written now. Discarding it would throw away a
        // listener's other choices to avoid a guess nobody has to make.
        val directory = File(root, AutomixProfileStore.DIRECTORY)
        directory.mkdirs()
        val file = File(directory, fileNameFor("track-8"))
        file.writeText(
            """{"version":3,"enabled":true,"style":"DJ_BLEND","bars":8,"filter":"SWEEP",""" +
                """"eq":"WARM","effects":"ECHO_TAIL","transitionStart":12.0}""",
        )

        val loaded = store().load("track-8")
        assertNotNull(loaded)
        assertEquals(8, loaded.bars)
        assertEquals(TransitionStyle.DJ_BLEND, loaded.style)
        assertEquals(FilterTransitionVariant.SWEEP, loaded.filter)
        assertEquals(TransitionEqShape.WARM, loaded.eq)
        assertEquals(AutomixEffect.ECHO_TAIL, loaded.effects)
        assertEquals(12.0, loaded.transitionStart)
        assertNull(loaded.volume, "a file written before the shape means 'leave the volume alone'")
        assertEquals(0.0, loaded.transitionEnd)
        assertTrue(file.exists(), "a version 3 choice is read, not thrown away")
    }

    @Test
    fun aChoiceWrittenNowIsStillRead() {
        // The migration must not cost the current schema anything: a file this
        // build wrote has to come back whole.
        val choice = AutomixOverride(
            style = TransitionStyle.DJ_FILTER,
            bars = 16,
            volume = TransitionVolumeShape.FADE_IN,
            transitionStart = 30.0,
            transitionEnd = 46.0,
        )
        store().save("track-9", choice)
        assertEquals(choice, store().load("track-9"))
    }

    @Test
    fun forgettingAChoiceRemovesIt() {
        val store = store()
        store.save("track-7", AutomixOverride(bars = 8))
        assertNotNull(store.load("track-7"))
        store.clear("track-7")
        assertNull(store.load("track-7"))
    }

    /** The same naming the store uses, so a hand-written file lands where it is looked for. */
    private fun fileNameFor(trackId: String): String = "${trackId.hashCode().toUInt()}_${trackId.length}.json"
}
