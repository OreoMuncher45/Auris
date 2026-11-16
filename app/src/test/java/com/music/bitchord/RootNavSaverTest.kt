package com.music.bitchord

import androidx.compose.runtime.saveable.SaverScope
import com.music.bitchord.ui.replay.ReplayStoryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RootNav.Saver] writes every field into one list and reads them back by
 * position, so a field added, dropped or reordered on one side only would
 * restore the wrong page after process death without failing anything else.
 */
class RootNavSaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(nav: RootNav): RootNav {
        val saved = with(RootNav.Saver) { scope.save(nav) }
        return RootNav.Saver.restore(saved!!)!!
    }

    /** Every Boolean flag, by name, so each can be set on its own. */
    private val flags: List<Pair<String, (RootNav) -> Boolean>> = listOf(
        "showSettings" to { it.showSettings },
        "showGenres" to { it.showGenres },
        "showAudiobooks" to { it.showAudiobooks },
        "showReplay" to { it.showReplay },
        "showReplayShare" to { it.showReplayShare },
        "showAccountScrobbling" to { it.showAccountScrobbling },
        "showSources" to { it.showSources },
        "showListenTogether" to { it.showListenTogether },
        "showEqualizer" to { it.showEqualizer },
        "showListenTogetherMembers" to { it.showListenTogetherMembers },
    )

    private fun RootNav.set(name: String) = when (name) {
        "showSettings" -> showSettings = true
        "showGenres" -> showGenres = true
        "showAudiobooks" -> showAudiobooks = true
        "showReplay" -> showReplay = true
        "showReplayShare" -> showReplayShare = true
        "showAccountScrobbling" -> showAccountScrobbling = true
        "showSources" -> showSources = true
        "showListenTogether" -> showListenTogether = true
        "showEqualizer" -> showEqualizer = true
        "showListenTogetherMembers" -> showListenTogetherMembers = true
        else -> error(name)
    }

    @Test fun `defaults survive a round trip`() {
        val restored = roundTrip(RootNav())
        assertEquals(0, restored.selectedTab)
        assertEquals(0, restored.lastSearchTab)
        assertEquals(ReplayStoryPage.INTRO, restored.replayLandingPage)
        assertEquals(null, restored.replayStory)
        assertEquals(null, restored.replaySharePage)
        assertEquals(null, restored.itunesPodcastId)
        assertEquals(null, restored.settingsCategory)
        assertEquals(null, restored.settingsSubScreen)
        flags.forEach { (name, read) -> assertEquals(name, false, read(restored)) }
    }

    @Test fun `every non-Boolean field restores to what was saved`() {
        val nav = RootNav().apply {
            selectedTab = 3
            lastSearchTab = 2
            replayLandingPage = ReplayStoryPage.ARTISTS
            replayStory = ReplayStoryPage.SONGS
            replaySharePage = ReplayStoryPage.SUMMARY
            itunesPodcastId = "1234"
            settingsCategory = "playback"
            settingsSubScreen = "equalizer"
        }
        val restored = roundTrip(nav)
        assertEquals(3, restored.selectedTab)
        assertEquals(2, restored.lastSearchTab)
        assertEquals(ReplayStoryPage.ARTISTS, restored.replayLandingPage)
        assertEquals(ReplayStoryPage.SONGS, restored.replayStory)
        assertEquals(ReplayStoryPage.SUMMARY, restored.replaySharePage)
        assertEquals("1234", restored.itunesPodcastId)
        assertEquals("playback", restored.settingsCategory)
        assertEquals("equalizer", restored.settingsSubScreen)
    }

    /** One flag at a time, so two Booleans swapped by position are caught. */
    @Test fun `each Boolean flag restores to itself and no other`() {
        flags.forEach { (name, _) ->
            val restored = roundTrip(RootNav().apply { set(name) })
            flags.forEach { (other, read) ->
                assertEquals("set $name, read $other", other == name, read(restored))
            }
        }
    }

    /** What a Bundle can hold: what the separate rememberSaveables wrote. */
    @Test fun `saved values are all Bundle-safe`() {
        val nav = RootNav().apply {
            replayStory = ReplayStoryPage.MINUTES
            itunesPodcastId = "x"
        }
        val saved = with(RootNav.Saver) { scope.save(nav) } as List<*>
        assertEquals(18, saved.size)
        saved.forEach { value ->
            assertTrue(
                "unexpected ${value?.javaClass}",
                value == null || value is Int || value is Boolean || value is String || value is Enum<*>,
            )
        }
    }
}
