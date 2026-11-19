package com.music.bitchord.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.isRtlText
import com.music.bitchord.ui.haptics.Haptic
import org.jetbrains.compose.resources.stringResource

/**
 * The portrait player's title and artist, with the like and menu glyphs
 * beside them.
 *
 * [modifier] places the row and [creditsModifier] the two lines of text; both
 * are built by the player, which owns the sleeve geometry they follow.
 * [scrolls] says whether the marquees may crawl, and is asked inside the
 * crossfade so the answer recomposes the two lines and nothing round them.
 */
@Composable
internal fun PortraitCredits(
    modifier: Modifier,
    creditsModifier: Modifier,
    song: Song,
    isAiTrack: Boolean,
    textGlow: Boolean,
    scrolls: () -> Boolean,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    showRevertCue: Boolean,
    onToggleLike: () -> Unit,
    onOpenMenu: () -> Unit,
    onMenuAnchor: ((IntRect) -> Unit)?,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(creditsModifier.weight(1f)) {
            // Only the title's own overflow gates the artist's stagger
            // below — an artist line that's long on its own has no
            // reason to wait on a title that already fits.
            var titleOverflowing by remember { mutableStateOf(false) }
            // Only while these credits are the screen. Collapsed into
            // a header over the queue or the lyrics they are a label
            // on a list, and a label that crawls pulls the eye off
            // whatever is being read below it.
            //
            // Asked inside the crossfade's own content, so the
            // switch recomposes the two lines and not the box
            // round the whole sleeve — and asked of the panels
            // first, so a collapse a tap asked for stops the crawl
            // with the tap, instead of a frame into the movement.
            // Targeted on the words rather than the videoId: two
            // cuts that share a title cross-fade into the exact
            // same text, which is nothing at all and leaves the
            // marquee where it was; a cut that renames the song
            // ("… (Live)") dissolves into the new one instead of
            // snapping while the rest of the switch moves around
            // it.
            //
            // Each instance draws the song it was keyed with: the
            // outgoing one keeps the words it is fading out, rather
            // than reading the new song and changing under its own
            // fade. See [CreditsKey].
            val creditsKey = CreditsKey(song, isAiTrack)
            // The newest payload drawn for each set of words, so an
            // outgoing instance fades out what was last on screen rather
            // than the first cut the crossfade happened to key it with.
            // Recorded after the pass that drew it, which is before any
            // later pass can make it outgoing.
            val payloads = remember { CreditsPayloads() }
            SideEffect { payloads.record(creditsKey) }
            Crossfade(
                targetState = creditsKey,
                animationSpec = tween(durationMillis = 300),
                label = "playerCredits",
            ) { key ->
                val drawn = payloads.shown(key, current = creditsKey)
                val shown = drawn.song
                val scrolling = scrolls()
                // The title wears the ink, like everything else on
                // the screen — unless the listener pinned a colour
                // for the text. The artist below it keeps the ink
                // itself: the second line of the block is not the
                // loudest thing on the screen.
                val ink = LocalPlayerText.current ?: LocalPlayerInk.current.value
                val artistInk = LocalPlayerText.current ?: LocalPlayerInk.current.value
                val alphas = LocalPlayerAlphas.current
                // The glow the text wears, read here rather than
                // in the screen above: the glow moves with the
                // ink, and a screen that read it would recompose
                // the whole player every time the ink did.
                val glow = LocalPlayerInkGlow.current.value
                val playerShadow = remember(glow, textGlow) {
                    if (textGlow) Shadow(color = glow, blurRadius = 9f) else null
                }
                Column {
                    MarqueeText(
                        text = shown.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = CREDITS_TITLE_SIZE,
                            lineHeight = 27.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = playerShadow,
                        ),
                        color = ink,
                        enabled = scrolling,
                        leading = if (shown.isExplicit == true) {
                            { ExplicitBadge(color = ink) }
                        } else {
                            null
                        },
                        onOverflowChange = { titleOverflowing = it },
                        rtl = isRtlText(shown.title),
                        // Only the tracks YouTube hands us a browse id for
                        // lead anywhere; the rest stay plain text.
                        modifier = Modifier.opensPage(shown.albumId, onOpenAlbum),
                    )
                    ArtistCreditsMarquee(
                        text = shown.artist,
                        credits = remember(shown.artist, shown.artists, shown.artistId) {
                            artistCredits(shown.artist, shown.artists, shown.artistId)
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = CREDITS_ARTIST_SIZE,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Normal,
                            shadow = playerShadow,
                        ),
                        // Under the title in weight and in strength,
                        // as the reference player sets it: the name
                        // is the second line of the block, not a
                        // second heading.
                        color = artistInk.copy(alpha = alphas.secondary * CREDITS_ARTIST_ALPHA),
                        enabled = scrolling,
                        // A title that's also scrolling gets to go first —
                        // starting together reads as clutter, so the artist
                        // waits a beat before it joins in.
                        startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
                        onOpenArtist = onOpenArtist,
                        rtl = isRtlText(shown.artist),
                    )
                    if (drawn.aiTrack ||
                        shown.genAi != com.music.bitchord.data.model.GenAiDisclosure.NONE
                    ) {
                        // Deliberately a small outline chip rather than a
                        // button: the listener asked for transparency, not
                        // another control competing with the transport.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .border(
                                    width = 1.dp,
                                    color = ink.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = if (shown.genAi == com.music.bitchord.data.model.GenAiDisclosure.PARTLY) {
                                    "AI · partly"
                                } else {
                                    "AI"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = ink.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        // Beside the credits rather than down in the toggle row:
        // liking is about *this song*, and the row below is about
        // how the queue plays. Guests get nothing to tap, since
        // there's no account to record it against — and neither
        // does a local file or a finished download, which carries
        // no YouTube identity to rate.
        //
        // Both marks are bare, with no disc behind them: the star
        // and the ellipsis are things about the song, and a filled
        // circle under each turned them into two more buttons on a
        // screen that already has a row of them.
        if (signedIn && song.localUri == null) {
            val liked = likeStatus == LikeStatus.LIKE
            CircleGlyph(
                icon = if (liked) PlayerIcons.Liked else PlayerIcons.LikedOff,
                contentDescription = stringResource(
                    if (liked) Res.string.remove_from_liked else Res.string.like,
                ),
                onClick = onToggleLike,
                haptic = if (liked) Haptic.ToggleOff else Haptic.ToggleOn,
                glyphSize = 24.dp,
                touchSize = 40.dp,
                disc = false,
                // A liked song says so: the filled star is written
                // at full strength, an empty one quietly.
                glyphAlpha = if (liked) {
                    LocalPlayerAlphas.current.primary
                } else {
                    LocalPlayerAlphas.current.tertiary
                },
            )
            Spacer(Modifier.width(2.dp))
        }
        CircleGlyph(
            icon = if (showRevertCue) PlayerIcons.Revert else PlayerIcons.More,
            contentDescription = stringResource(Res.string.more),
            onClick = onOpenMenu,
            glyphSize = 22.dp,
            touchSize = 40.dp,
            disc = false,
            // Beside the title, and quiet: the reference player
            // writes it at half strength, where this one was the
            // brightest mark on the screen.
            glyphAlpha = LocalPlayerAlphas.current.tertiary,
            modifier = Modifier.reportMenuAnchor(onMenuAnchor),
        )
    }
}

/**
 * What the credits crossfade is keyed on: the words, not the track.
 *
 * Two cuts that share a title and an artist are one key, so switching between
 * them crossfades nothing and leaves the marquee where it was. The key still
 * carries what the credits were drawn from — the song and whether it is known
 * to be AI-generated — so an outgoing instance goes on drawing its own title,
 * artist and badges while it fades.
 */
internal class CreditsKey(val song: Song, val aiTrack: Boolean) {
    override fun equals(other: Any?): Boolean =
        other is CreditsKey && other.song.title == song.title && other.song.artist == song.artist

    override fun hashCode(): Int = 31 * song.title.hashCode() + song.artist.hashCode()
}

/**
 * The newest payload drawn for each set of credits words.
 *
 * The crossfade holds on to the first key of each set of words it sees: a cut
 * that shares them (same title and artist, new album link or AI mark) is equal
 * to it and replaces nothing. So when the words change, the outgoing instance
 * is handed that first key. This keeps the payload last drawn for those words,
 * so the outgoing instance fades out what was actually on screen.
 *
 * A handful of entries is plenty: only the instances still fading can ask.
 */
internal class CreditsPayloads(private val capacity: Int = 8) {
    private val latest = object : LinkedHashMap<CreditsKey, CreditsKey>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<CreditsKey, CreditsKey>): Boolean =
            size > capacity
    }

    /** [current] is what is on screen now for its words. */
    fun record(current: CreditsKey) {
        latest[current] = current
    }

    /**
     * What an instance of the crossfade keyed with [key] draws. The current
     * target draws [current], made from the newest song, so a cut that shares
     * its words still shows its own album link and badges; an outgoing one
     * draws the payload last drawn for its words, or [key] itself if none was
     * recorded.
     */
    fun shown(key: CreditsKey, current: CreditsKey): CreditsKey =
        if (key == current) current else latest[key] ?: key
}
