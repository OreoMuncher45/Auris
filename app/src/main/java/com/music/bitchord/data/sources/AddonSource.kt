package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonException
import com.music.bitchord.data.sources.addon.AddonManifest
import com.music.bitchord.data.sources.addon.AddonNotFound
import com.music.bitchord.data.sources.addon.AddonStream
import com.music.bitchord.data.sources.addon.AddonTrack
import com.music.bitchord.playback.StreamContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

/**
 * A [MusicSource] backed by one addon server the user pointed at.
 *
 * The [config]'s [SourceConfig.baseUrl] is the addon's own URL — the thing
 * pasted on the sources screen, token and all. Everything below that is
 * [AddonClient]: this class is the translation between what the addon says and
 * what the rest of the app understands, and it is deliberately the only place
 * that knows both vocabularies.
 *
 * Where [ModuleSource] has to fan out across every plugin in an index and
 * reconcile their answers, one addon is one catalogue and one search. There is
 * no interleaving, no per-module budget and no engine pool, which is most of
 * why this file is a third the length of that one. A user wanting two
 * catalogues adds two addons, and [SourceRegistry] walks them in order.
 */
class AddonSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.displayName

    private val client = AddonClient(config.baseUrl)

    /**
     * Rows this addon has recently handed over, by their own track id.
     *
     * Two things need them after the fact and neither is reachable from a bare
     * id. The first is the duration the catalogue claimed, which
     * [QualityUpgrade][com.music.bitchord.playback.QualityUpgrade] checks a
     * substitute against before cutting it into a track already playing — a
     * match on title and artist alone has put a 189s cut under a 163s
     * recording before. The second is the row's own `streamURL`, which the
     * spec allows in place of a `/stream` answer and which is the only thing
     * left to try when that endpoint has nothing.
     *
     * Bounded and cleared wholesale rather than pruned: it is an optimisation
     * on both counts, and a miss costs a field of metadata, not a track.
     */
    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, AddonTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, AddonTrack>) = size > MAX_ROWS
        },
    )

    // ── Manifest policy ───────────────────────────────────────────────────

    /** The stored config as it is now: the manifest switches change without this source being rebuilt. */
    private val live: SourceConfig get() = SourceRegistry.config(config.id) ?: config

    /**
     * Whether this addon can answer at all.
     *
     * It used to also refuse while `checkValidLossless` was set and the
     * connected output could not carry lossless. That made a turned-on lossless
     * addon unreachable on any Bluetooth device, leaving YouTube as the only
     * source in the walk — see [SourceConfig.losslessOutputMissing] for why that
     * is the wrong trade. It is asked regardless now, and the output device
     * does what it does with the bytes.
     *
     * Disabled addons are still refused, which is what this has to be for.
     */
    private fun shutOut(what: String): Boolean {
        if (live.enabled) return false
        TrackLog.d(TAG, "${config.displayName}: switched off; not asking it for $what")
        return true
    }

    /**
     * [shutOut], then the manifest's current answer, then [shutOut] again — so
     * an addon that has just been switched off is stopped before the request
     * rather than after it. The manifest is the client's shared, cached copy,
     * which the request was about to read for its settings anyway.
     */
    private suspend fun admitted(what: String): Boolean {
        if (shutOut(what)) return false
        client.manifest().getOrNull()?.let(::recordPolicy)
        return !shutOut(what)
    }

    private fun recordPolicy(manifest: AddonManifest) =
        SourceRegistry.updatePolicy(config.id, manifest.downloadsAllowed, manifest.requiresLosslessOutput)

    // ── Health ────────────────────────────────────────────────────────────

    /**
     * Whether this addon is there and is one this app can use.
     *
     * The manifest answers all three questions at once — reachable, well
     * formed, and declaring the two resources a track needs — which is why the
     * distinction [SourceHealth] draws is available here for free.
     * [AddonException] is the addon or its URL being wrong in a way no amount
     * of retrying fixes, and so is a rejection; anything else is a server
     * having a bad minute and is worth trying again on the next track.
     */
    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) {
            return@withContext SourceHealth.Rejected("An addon URL is required")
        }
        if (shutOut("a health check")) {
            return@withContext SourceHealth.Rejected(NEEDS_LOSSLESS_OUTPUT)
        }
        client.manifest().fold(
            onSuccess = { manifest ->
                recordPolicy(manifest)
                SourceHealth.Ok(
                    listOfNotNull(
                        manifest.displayName.takeIf { it.isNotBlank() },
                        manifest.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    ).joinToString(" ").ifBlank { null },
                )
            },
            onFailure = { failure ->
                // No readable manifest is not the same as no addon. The
                // endpoints are what this app uses; the manifest only describes
                // them, and an addon that never published one is healthy the
                // moment it answers a search. Reported plainly rather than as a
                // success with a hidden caveat, so the row says which of the two
                // kinds of working this is.
                if (client.probeSearch().isSuccess) {
                    TrackLog.d(TAG, "${config.displayName}: no manifest, but search answers")
                    return@fold SourceHealth.Ok("No manifest · search works")
                }
                val reason = failure.message ?: "Could not reach the addon"
                if (failure is AddonException) {
                    SourceHealth.Rejected(reason)
                } else {
                    TrackLog.w(TAG, "addon unreachable for ${config.displayName}: $reason")
                    SourceHealth.Unreachable(reason)
                }
            },
        )
    }

    /**
     * What the addon calls itself, or null if it has not said yet.
     *
     * The sources screen stores this into the config's label so the row reads
     * "Unified · Quality First" rather than "unified-addon.netlify.app". Asking
     * the addon is the only honest way to get that: a hostname is where a thing
     * is, not what it is, and there is no reason to make a user invent a name
     * for something that already published one.
     *
     * Free after [health] — the manifest is shared and cached by the client, so
     * this reads what the probe already fetched.
     */
    suspend fun manifestName(): String? =
        if (shutOut("its name")) null else client.manifest().getOrNull()?.displayName?.ifBlank { null }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * One request, one catalogue, in the order the addon returned it.
     *
     * [waitForAll] is ignored, and has nothing here to mean: it exists so a
     * background pass can wait out the slow plugin in a module index, and an
     * addon is a single call whose entire cost is one HTTP round trip.
     *
     * The tier is [request]'s when a stream is what this search is for, and
     * lossless otherwise. Asking at the tier that will actually be streamed is
     * the point: a tier-aware catalogue describes its rows differently per
     * tier, and a metered listener searching for something to play at 128kbps
     * was being handed rows advertising a FLAC this app was never going to ask
     * that addon for — ranked on a quality claim that would not survive the
     * `/stream` call. A search with no request behind it is the search box,
     * where the best the catalogue holds is exactly what should be shown.
     *
     * The order is left exactly as it arrived.
     * [TrackMatcher][com.music.bitchord.data.sources.TrackMatcher] decides which
     * rows are the recording and [SourceResolver] decides which to open; an
     * addon knows its own catalogue better than a re-sort here would.
     */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            if (!admitted("a search")) return@withContext emptyList()
            val tier = request?.tier ?: AddonClient.TIER_LOSSLESS
            val tracks = client.search(query, tier).getOrElse { failure ->
                TrackLog.w(TAG, "${config.displayName}: search failed — ${failure.message}")
                return@withContext emptyList()
            }
            tracks.asSequence()
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .take(limit)
                .map { track ->
                    rows[track.id] = track
                    Song(
                        videoId = SourceRegistry.trackKey(config.id, track.id),
                        title = track.title,
                        artist = track.artist,
                        albumName = track.album.ifBlank { null },
                        thumbnailUrl = track.artwork,
                        durationText = track.durationSec?.let {
                            "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}"
                        },
                        // Atmos is read before the tier label and not from it.
                        // Tidal publishes its immersive rows as
                        // `audioQuality: LOW`, so a row scored on that label
                        // alone sorts the one mix the listener turned Atmos on
                        // for *below* every stereo row — see
                        // [AddonTrack.isDolbyAtmos].
                        sourceQuality = if (track.isDolbyAtmos) {
                            ModuleSource.DOLBY
                        } else {
                            ModuleSource.qualityTier("${track.audioQuality} ${track.format}")
                        },
                    )
                }
                .toList()
        }

    // ── Stream ────────────────────────────────────────────────────────────

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            if (!admitted("a stream")) return@withContext null
            val tier = request.tier
            // The immersive rung, asked first and as its own quality value.
            //
            // This is the difference the report "Atmos is enabled and the same
            // addon plays it in another player" turned on: `quality` and
            // `atmos` are two questions. `atmos=auto` only says the host
            // *permits* a spatial answer; a backend that files the spatial mix
            // under its own tier answers `quality=atmos` and hands back the
            // stereo ladder for `quality=lossless`, whatever the hint says.
            // The reference host always asks the Atmos tier first and only
            // then walks down (atmos → hi_res → lossless → high); this is the
            // same walk with the rungs this app has.
            //
            // A miss here costs one request and nothing else. Anything short of
            // a playable immersive answer — an error, a stereo reply, an
            // encrypted or malformed URL — falls through to the ordinary
            // request below, so a track with no Atmos master resolves exactly
            // as it did before.
            if (request is StreamRequest.Lossless && atmosWanted()) {
                val immersive = client.stream(trackId, AddonClient.TIER_ATMOS).getOrNull()
                val opened = immersive?.let { answer ->
                    answer.url.ifBlank { null }?.let { url ->
                        openable(url, answer, trackId, AddonClient.TIER_ATMOS)
                    }
                }
                if (opened != null && opened.format.isDolbyAtmos) return@withContext opened
            }
            val result = client.stream(trackId, tier)
            val answer = result.getOrNull()

            if (answer == null) {
                // A 404 is the addon saying it does not hold this recording,
                // which is a miss and belongs in no log. Anything else is a
                // fault worth a line, and both end the same way: whatever the
                // search row had, else null so [SourceResolver] moves on.
                val failure = result.exceptionOrNull()
                if (failure !is AddonNotFound) {
                    TrackLog.w(TAG, "${config.displayName}: stream failed for $trackId — ${failure?.message}")
                }
                return@withContext fromRow(trackId, tier)
            }

            val url = answer.url.ifBlank { null } ?: run {
                // The addon answered, and said it has nothing for this one. Its
                // own words when it gave any: some report a backend that came
                // up empty, others a credential the user has to go and set on
                // the addon's own setup page — and that second kind is only
                // ever actionable if it is said out loud here.
                TrackLog.w(
                    TAG,
                    "${config.displayName}: no stream for $trackId" +
                        (answer.error?.ifBlank { null }?.let { " — $it" } ?: ""),
                )
                return@withContext fromRow(trackId, tier)
            }

            openable(url, answer, trackId, tier)
        }

    /**
     * The stream the search row carried, when the `/stream` call had nothing.
     *
     * The spec allows a row to publish its own `streamURL` and says a host may
     * skip the stream call entirely when one is present. This app does not skip
     * it — a row's URL comes with none of the codec, container and transport
     * metadata that decides how the player opens it, and giving that up to save
     * one round trip is a poor trade. As a *fallback* it is worth having: an
     * addon that serves permanent direct links and treats `/stream` as optional
     * is otherwise unplayable here for no good reason.
     */
    private fun fromRow(trackId: String, tier: String): SourceStream? {
        val row = rows[trackId] ?: return null
        val url = row.streamURL?.ifBlank { null } ?: return null
        TrackLog.d(TAG, "${config.displayName}: using the search row's own URL for $trackId")
        return openable(url, AddonStream(url = url, format = row.format), trackId, tier)
    }

    /**
     * Everything between a URL the addon named and one the player may open.
     *
     * Three refusals, each of which is the only place it can be caught:
     *
     *  - **Unopenable.** [ModuleSource.malformed] is OkHttp's own parser plus
     *    the doubled-origin test, asked here where refusing costs nothing.
     *  - **Encrypted.** The spec forbids an addon sending a protected rendition
     *    unless the request asked for one, and this app never does. Handing the
     *    player bytes it has no key for fails later and less clearly.
     *  - **Immersive, on a device that can't.** [ModuleSource.unplayable] again,
     *    for exactly the reason set out there: an Atmos rendition is routinely
     *    the *only* one offered for a track, so there is no lower sibling to
     *    fall back to and refusing is what lets another source be heard.
     */
    /**
     * Whether this request should go looking for an immersive answer: the
     * phone can decode one and the listener asked for one — by the master
     * switch or by the Dolby Atmos quality rung on this connection. The same
     * two answers [SourceResolver] and [ModuleSource.unplayable] read.
     */
    private fun atmosWanted(): Boolean =
        DeviceCodecs.playsDolbyAtmos && AppSettings.wantsDolbyAtmos

    private fun openable(url: String, answer: AddonStream, trackId: String, tier: String): SourceStream? {
        if (ModuleSource.malformed(url)) {
            TrackLog.w(
                TAG,
                "${config.displayName}: malformed URL for $trackId; skipping it — ${url.take(120)}",
            )
            return null
        }
        if (answer.isEncrypted) {
            TrackLog.w(
                TAG,
                "${config.displayName}: $trackId came back encrypted, which Auris never asked for — passing",
            )
            return null
        }

        val format = formatOf(answer, url, tier)
        val playsAtmos = DeviceCodecs.playsDolbyAtmos
        if (ModuleSource.unplayable(format, atmosAllowed = atmosWanted())) {
            val why = if (playsAtmos) {
                "which is switched off in Settings"
            } else {
                "which this device has no decoder for"
            }
            TrackLog.w(
                TAG,
                "${config.displayName}: answered a $tier request with ${format.summary}, $why — passing",
            )
            return null
        }

        // Said out loud rather than left to be sniffed. An addon serving an HLS
        // playlist from an extensionless path — which the reference addon does,
        // at `/dash/{id}` — is the exact shape that reaches a progressive
        // extractor and fails as an unreadable container. The addon told us
        // what it is; the player only needed to be passed it on.
        answer.transport?.let { StreamContainer.declare(url, it) }
        if (format.isDolbyAtmos) {
            TrackLog.i(
                "ATMOS",
                "${config.displayName}: $trackId → ${format.summary} " +
                    "(${answer.transport ?: "no transport"}, decoder=$playsAtmos, " +
                    "atmos=${AppSettings.wantsDolbyAtmos})",
            )
        }

        return SourceStream(
            url = url,
            format = format,
            durationSec = rows[trackId]?.durationSec,
        )
    }

    /**
     * What is really on the end of the URL, from what the addon said about it.
     *
     * Ordered by how much each claim is worth. A stated `codec` is the addon
     * answering the question directly and is taken as given. A container names
     * the envelope, which for `flac`, `wav` and `mp3` settles the codec too. A
     * mime type is next, then a quality label naming a lossless tier, and last
     * the URL's own extension — a guess, but the one that catches a server that
     * has quietly walked down its fallback chain and handed back `.128.mp3`.
     *
     * Null when none of them knows, which is left null rather than guessed at:
     * [StreamFormat.isLossless] answering "unknown" is information, and
     * answering "no" when it does not know is not.
     */
    private fun formatOf(answer: AddonStream, url: String, tier: String): StreamFormat {
        val stated = normalizedCodec(answer.statedCodec)
        val container = answer.statedContainer?.takeIf { it in SELF_DESCRIBING_CONTAINERS }
        val mime = normalizedCodec(
            answer.mimeType?.substringAfterLast('/')?.substringBefore(';')?.trim(),
        )
        val extension = normalizedCodec(
            url.substringBefore('?').substringAfterLast('.').takeIf { it.isNotEmpty() },
        )

        val codec = when {
            // An answer the addon flags as immersive *is* E-AC-3 with joint
            // object coding, and that outranks anything that only names the
            // envelope. This chain used to run codec -> container -> mime
            // first, so an addon stating `codec: "eac3"` or `"mp4"` — or a
            // generic `audio/mp4` — won before the Atmos hint was ever
            // consulted, and a stream the listener asked to hear as Atmos was
            // ranked, badged and refused as an ordinary lossy file. A codec
            // that names a different, specific payload is still trusted: FLAC
            // is a more specific claim than a quality label, and a stream that
            // truly says FLAC is not made immersive by one.
            answer.isDolbyAtmos &&
                (stated == null || stated == DOLBY_ATMOS || stated == "eac3" || stated in CONTAINERS) ->
                DOLBY_ATMOS
            stated != null -> stated
            container != null -> container
            mime != null -> mime
            else -> if (ModuleSource.qualityTier(answer.qualityText) == ModuleSource.LOSSLESS) {
                "flac"
            } else {
                extension
            }
        }

        return StreamFormat(
            codec = codec,
            // The tier's own published meaning is the last resort and only for
            // the lossy rungs, where "HIGH" is a statement of 320 and the
            // number is what says "this is not what you asked for".
            kbps = answer.kbps ?: when (tier) {
                AddonClient.TIER_HIGH -> 320
                AddonClient.TIER_LOW -> 128
                else -> null
            },
            sampleRateHz = answer.sampleRateHz,
            bitDepth = answer.bits,
        )
    }

    /**
     * One codec id from whichever spelling an addon used.
     *
     * The Dolby family is the reason this exists. The same payload arrives as
     * `eac3_joc`, `eac3-joc`, `ec-3`, `ec3`, `eac3`, `ac-3` or `ac3` depending
     * on the backend, and [StreamFormat.isDolbyAtmos] compares against one
     * hyphenated spelling. Passing the raw field through is what made the
     * underscore payload the tests carry depend on a fallback branch; anything
     * else — a plain `eac3`, say — fell straight into the lossy ladder and
     * never reached the Atmos label at all. Normalising here makes every
     * spelling one codec before anything decides what it is.
     */
    private fun normalizedCodec(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when (value) {
            in JOC_CODECS -> DOLBY_ATMOS
            in EAC3_CODECS -> "eac3"
            "ac-3", "ac3" -> "ac3"
            else -> value.takeIf { it in AUDIO_CODECS }
        }
    }

    /** What this app asks an addon for, in the vocabulary the reference host uses. */
    private val StreamRequest.tier: String
        get() = when (this) {
            is StreamRequest.Lossless -> AddonClient.TIER_LOSSLESS
            is StreamRequest.Best -> AddonClient.TIER_HIGH
            is StreamRequest.Capped ->
                if (maxKbps <= ModuleSource.LOW_CEILING_KBPS) AddonClient.TIER_LOW else AddonClient.TIER_HIGH
        }

    /** Dropped along with the source it belongs to — see [SourceRegistry.publish]. */
    fun release() {
        rows.clear()
        client.clear()
    }

    /** Forces the next completed track lookup back onto the network. */
    fun clearCompletedTrackCalls() {
        client.clearCompletedTrackCalls()
    }

    private companion object {
        const val TAG = "BitChord"

        /** What a gated addon's health says, for the editor's Test line. The sources list draws its own. */
        const val NEEDS_LOSSLESS_OUTPUT =
            "This addon only works with a lossless output — use a lossless supported device"

        /** How many search rows to remember. A long queue's worth, several times over. */
        const val MAX_ROWS = 256

        /**
         * Containers that name their own codec. A `.flac` holds FLAC and
         * nothing else; an `mp4` or an `ogg` holds any of several things and
         * says nothing on its own, so those are deliberately absent.
         */
        val SELF_DESCRIBING_CONTAINERS = setOf("flac", "wav", "mp3", "aiff")

        /**
         * What may be believed as a codec, from a field or from a URL's
         * extension.
         *
         * `mp4` is in the list and is the interesting one. It names a container
         * rather than a codec, so it is not in [SELF_DESCRIBING_CONTAINERS] and
         * never overrides something better — but leaving it out entirely was
         * wrong in a way worth spelling out. A live addon's JioSaavn backend
         * answers `{"format": "mp4", "quality": "320kbps"}` with a URL ending
         * `_320.mp4`, and with `mp4` unrecognised *nothing* named the codec: the
         * format came back with a null one, and a null codec means
         * [StreamFormat.isLossless] answers "unknown" rather than "no".
         *
         * Unknown is not a harmless answer here. It is the answer that lets a
         * 320kbps AAC stream stay in contention against a FLAC when
         * [SourceResolver] is choosing between them, on the grounds that nobody
         * could rule it out. Recording the container says the true and useful
         * thing — this is MP4, and MP4 is not a lossless container — without
         * inventing the codec inside it.
         */
        val AUDIO_CODECS = setOf(
            "flac", "alac", "wav", "aiff", "mp3", "aac", "he-aac", "m4a", "mp4",
            "ogg", "opus", "vorbis", "webm", "eac3-joc", "ec3-joc", "eac3", "ac3",
        )

        /** The codec id every immersive answer is recorded as. */
        const val DOLBY_ATMOS = "eac3-joc"

        /** Joint-object-coding spellings, all of which mean the Atmos mix. */
        val JOC_CODECS = setOf("eac3-joc", "eac3_joc", "ec3-joc", "ec3_joc", "dolby-atmos")

        /** The E-AC-3 core, which may carry Atmos objects or may be a plain bed. */
        val EAC3_CODECS = setOf("eac3", "eac-3", "ec-3", "ec3")

        /**
         * Envelopes an addon may put in its `codec` field. Unlike
         * [SELF_DESCRIBING_CONTAINERS] these say nothing about the payload, so
         * they lose to a flagged Atmos answer rather than overriding it.
         */
        val CONTAINERS = setOf("mp4", "m4a", "webm", "ogg")
    }
}
