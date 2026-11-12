package com.music.bitchord.data.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Spotify's own home feed, as the web player draws it.
 *
 * ## What is in it
 *
 * A greeting ("Good evening"), a row of chips the listener can narrow it
 * with, and a list of sections — each a titled shelf of cards. The first
 * section is usually untitled and holds the quick-access grid (Liked Songs,
 * the things you jumped back into), and the rest are the shelves: "Jump back
 * in", "Made for you", "Your favourite mixes", and so on.
 *
 * ## Cards, not tracks
 *
 * Every card in the feed is a *collection* — a playlist, an album, an artist,
 * a show — and not a track. That is the shape Spotify's own home has, and it
 * is the shape this app wants, for a reason that is not only looks: a shelf of
 * tracks has to be matched to something playable before it can be drawn, and
 * matching a track means a search. Twelve tracks per shelf, ten shelves, is a
 * hundred and twenty searches before the page is finished. A shelf of cards
 * costs none: the search happens when the listener opens one.
 *
 * So the feed is read as cards here, and turned into playable tracks further
 * down, where the listener asked for them.
 */
internal data class SpotifyHomeFeed(
    val greeting: String,
    /** The chips the feed offers, in the order it lists them. */
    val chips: List<SpotifyChip>,
    /** The shelves, in the order the feed returns them. */
    val sections: List<SpotifyHomeSection>,
) {
    /**
     * The quick-access grid: the feed's untitled section, when it has one.
     *
     * Spotify's web home opens with an untitled shelf of shortcuts — the
     * little square tiles above the shelves — and puts the shelves after it.
     * A feed that leads with a titled shelf has no grid, and that is fine:
     * this is null and the page draws no grid.
     */
    val shortcuts: List<SpotifyCard>
        get() = sections.firstOrNull()?.takeIf { it.title.isBlank() }?.cards.orEmpty()

    /** The titled shelves — everything but the grid. */
    val shelves: List<SpotifyHomeSection>
        get() = sections.drop(if (sections.firstOrNull()?.title?.isBlank() == true) 1 else 0)
}

/** One chip the feed can be narrowed by. */
internal data class SpotifyChip(
    /** Spotify's own id — `music-chip`, `podcasts-chip`, and so on. */
    val id: String,
    val label: String,
    /** The chips that open beside this one, such as *Following*. */
    val subChips: List<SpotifyChip>,
)

/** One shelf of the feed. */
internal data class SpotifyHomeSection(
    val title: String,
    val subtitle: String,
    val cards: List<SpotifyCard>,
)

/** One card on a shelf. */
internal data class SpotifyCard(
    /** The Spotify uri: `spotify:playlist:…`, `spotify:album:…`. */
    val uri: String,
    val id: String,
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
) {
    /** What kind of collection the card opens. */
    enum class Kind { PLAYLIST, ALBUM, ARTIST, SHOW, TRACK, CATEGORY, OTHER;

        companion object {
            /** The kind a Spotify uri names, or [OTHER]. */
            fun of(uri: String): Kind = when {
                uri.startsWith("spotify:playlist:") -> PLAYLIST
                uri.startsWith("spotify:album:") -> ALBUM
                uri.startsWith("spotify:artist:") -> ARTIST
                uri.startsWith("spotify:show:") || uri.startsWith("spotify:episode:") -> SHOW
                uri.startsWith("spotify:track:") -> TRACK
                uri.startsWith("spotify:page:") -> CATEGORY
                else -> OTHER
            }
        }
    }
}

/** Reads the home feed out of the web player's answer. */
internal object SpotifyHomeFeedParser {

    /**
     * The feed in [root], which is the `data` of a `home` answer.
     *
     * Everything is skipped rather than thrown on: a shelf whose card carries
     * a shape this version does not know is one missing card, and a feed with
     * one missing card is still a feed. What cannot be read at all — no
     * `home` in the answer — is an empty feed, which the caller reports.
     */
    fun parse(root: JsonObject): SpotifyHomeFeed {
        val home = root.obj("home") ?: return SpotifyHomeFeed("", emptyList(), emptyList())
        val sections = home.obj("sectionContainer")
            ?.obj("sections")?.arr("items").orEmpty()
            .mapNotNull { section -> parseSection(section) }

        return SpotifyHomeFeed(
            greeting = home.str("greeting.transformedLabel") ?: home.str("greeting.text") ?: "",
            // The long-form chips are not drawn: each one opens a shelf of
            // shows or books, and there is nothing here that can play either.
            // See [SpotifyLongForm].
            chips = home.arr("homeChips").orEmpty().mapNotNull { chip -> parseChip(chip) }
                .filterNot { SpotifyLongForm.isLongFormChip(it.id) },
            sections = sections,
        )
    }

    private fun parseSection(element: JsonElement): SpotifyHomeSection? {
        val section = element.objOrNull() ?: return null
        val data = section.obj("data")
        val title = data?.str("title.transformedLabel") ?: data?.str("title.text") ?: ""
        val subtitle = data?.str("subtitle.transformedLabel") ?: data?.str("subtitle.text") ?: ""
        val items = section.obj("sectionItems")?.arr("items").orEmpty()
        val cards = items.mapNotNull { item -> parseCard(item) }
        // A shelf with nothing in it is not drawn. An empty shelf is a
        // heading over a blank space, which is worse than no heading.
        if (cards.isEmpty()) return null
        return SpotifyHomeSection(title, subtitle, cards)
    }

    private fun parseChip(element: JsonElement): SpotifyChip? {
        val chip = element.objOrNull() ?: return null
        val id = chip.str("id")?.takeIf { it.isNotBlank() } ?: return null
        // A long-form chip is dropped whole, subchips and all: the subchips
        // are the shelves that open beside it, and they are long-form too.
        if (SpotifyLongForm.isLongFormChip(id)) return null
        val label = chip.str("label.transformedLabel") ?: chip.str("label.text") ?: id
        return SpotifyChip(
            id = id,
            label = label,
            subChips = chip.arr("subChips").orEmpty().mapNotNull { parseChip(it) },
        )
    }

    /**
     * One card, from the item's own `content` wrapper.
     *
     * The wrapper's shape changes with the kind of thing it holds, so the
     * title and the picture are looked for in the places each kind puts them:
     * an artist has a profile and an avatar, a playlist has images, an album
     * has cover art.
     *
     * Internal rather than private because the category pages hold the same
     * cards: a browse section's entries are the feed's entries, one shelf
     * lower down.
     */
    internal fun parseCard(element: JsonElement): SpotifyCard? {
        val item = element.objOrNull() ?: return null
        val content = item.obj("content") ?: return null
        val data = content.obj("data") ?: return null
        val uri = data.str("uri") ?: content.str("uri") ?: item.str("uri") ?: return null
        val kind = SpotifyCard.Kind.of(uri)
        if (kind == SpotifyCard.Kind.OTHER) return null
        // A show, an episode or a book is not a card: there is nothing here
        // that can play one, and a card that opens an empty shelf is worse
        // than no card. See [SpotifyLongForm].
        if (kind == SpotifyCard.Kind.SHOW || SpotifyLongForm.isLongFormUri(uri)) return null

        val title = data.str("name")
            ?: data.str("profile.name")
            ?: data.str("displayName")
            ?: return null
        if (title.isBlank()) return null

        val subtitle = when (kind) {
            SpotifyCard.Kind.PLAYLIST -> data.str("ownerV2.data.name") ?: data.str("description").orEmpty()
            SpotifyCard.Kind.ALBUM -> data.artists()
            SpotifyCard.Kind.TRACK -> data.artists()
            SpotifyCard.Kind.ARTIST -> ""
            SpotifyCard.Kind.SHOW -> data.str("publisher.name") ?: data.str("podcastV2.data.name").orEmpty()
            SpotifyCard.Kind.CATEGORY -> ""
            SpotifyCard.Kind.OTHER -> ""
        }

        return SpotifyCard(
            uri = uri,
            id = uri.substringAfterLast(':'),
            kind = kind,
            title = title,
            subtitle = subtitle,
            imageUrl = data.bestImage(),
        )
    }

    /** The artists of a track or an album, as one line. */
    private fun JsonObject.artists(): String {
        val items = obj("artists")?.arr("items").orEmpty()
        val names = items.mapNotNull { artist ->
            artist.objOrNull()?.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }
        if (names.isNotEmpty()) return names.joinToString(", ")
        // Some answers list the first artist under its own key.
        return str("firstArtist.items.0.profile.name").orEmpty()
    }

    /**
     * The card's picture, at the largest size offered.
     *
     * The places differ by kind — an artist has an avatar, a playlist has
     * images, an album has cover art — and the sizes within one place are in
     * no fixed order, so the widest source is chosen rather than the first.
     */
    private fun JsonObject.bestImage(): String? {
        val sources = obj("coverArt")?.arr("sources").orEmpty()
            .ifEmpty { obj("visuals")?.obj("avatarImage")?.arr("sources").orEmpty() }
            .ifEmpty { obj("images")?.arr("items").orEmpty().flatMap { it.objOrNull()?.arr("sources").orEmpty() } }
            .ifEmpty { obj("avatar")?.arr("sources").orEmpty() }
            .ifEmpty { obj("image")?.arr("sources").orEmpty() }
            .ifEmpty { obj("albumOfTrack")?.obj("coverArt")?.arr("sources").orEmpty() }
        return sources.mapNotNull { source ->
            val source = source.objOrNull() ?: return@mapNotNull null
            val url = source.str("url") ?: return@mapNotNull null
            url to (source.int("width") ?: 0)
        }.maxByOrNull { it.second }?.first
    }

}
