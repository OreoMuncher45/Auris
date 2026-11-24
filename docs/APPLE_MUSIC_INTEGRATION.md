# Apple Music integration — implementation plan

Status: **plan of record**. Written after reading:

- `jackychan4891/BitChord-PC-Official`, `src/apple.rs`, added in commit
  **`9b0cc55` — "BitChord PC Continued 2.0: native Rust backend and release prep"**.
  That is where the Apple Music **catalog home** ("the homescreen library") landed.
  It has no Amazon-style search: it scrapes the web player's `serverData` and
  reads `amp-api` for collections/motion/descriptions.
- `Ekanshe110/Apple-Auris`: addon-backed Apple **catalog + account library**
  (`data/apple/*`, `AddonClient` library routes, `docs/APPLE_LIBRARY_ENDPOINT.md`).
  It deliberately calls **no** Apple API itself; an external addon owns the Apple
  account and its token.
- Our tree (`test/v3.3-3.4-unified`) already has the anonymous **web-player
  bearer scrape** and the `amp-api.music.apple.com/v1/catalog/<sf>/search`
  client in `app/.../data/canvas/AppleMusicCanvas.kt`, used for motion artwork.

## What the user asked for

1. Recreate BitChord-PC's Apple Music **home/catalog** in Auris Android.
2. Add **search** (BitChord-PC lacks it).
3. Apple Music **account library** (own playlists/albums/songs) if cheap;
   metadata + search is the must.
4. Reuse the "Auris-exclusive addon" token route for library/playlists where
   possible; account-free search is desirable.

## Hard constraint — Apple audio is DRM

Apple's catalog **streams are FairPlay/DRM**. A scraped anonymous bearer can read
**metadata, artwork, motion and descriptions**, and a `music-user-token` can read
**the account's library**, but neither can decode the audio. Therefore:

- Apple Music in Auris is a **catalog/browse/search + library-metadata** provider.
- A tapped Apple track is resolved for playback through the app's **existing
  multi-source matcher** (Tidal-first, lossless-only), exactly as Spotify rows
  are. If no source matches, the row is shown but marked unplayable — never a
  silent substitution of a different recording.
- The only path to actual Apple audio would be the addon (`streamId` in the
  Apple-Auris contract), which is out of scope for this plan unless the addon is
  installed.

## What already exists (reuse, don't rebuild)

| Need | Existing code |
| --- | --- |
| Anonymous web-player bearer (scrape, cache, `exp`, 401 re-scrape) | `AppleMusicCanvas.token()` / `get()` / `isWebPlayerToken` / `expiry` |
| `amp-api` base, storefront | `AppleMusicCanvas` (`AMP`, `storefront`) |
| Catalog search (songs/albums) + result scoring | `AppleMusicCanvas.search*` |
| Home provider switch (YouTube / Spotify) | `shared/.../data/settings/HomeProvider.kt` |
| Search tabs + `SearchResult` models | `data/spotify/SpotifySearch.kt`, `SearchResult`, `SearchFilter` |
| Multi-source playback matching | `data/sources/*`, `PlaybackStreamResolver`, `YtMusicRepository` |
| Apple motion artwork for the player | `AppleMusicCanvas`, `CanvasRepository` |

## Architecture

```
Settings ── HomeProvider.APPLE ──┬── MainViewModel.loadHome (Apple shelves)
                                 └── search source axis (Apple)
                                            │
                     data/apple/AppleMusicApi  (thin amp-api + bearer)
                        │            │                 │
             search(term)      catalog/albums/…   web serverData home
                        │            │                 │
             AppleMusicMappers  →  Song / ShelfItem / DetailPage / SearchResult
                                            │
                              existing source matcher → playable stream
```

### New files

| File | Responsibility |
| --- | --- |
| `app/.../data/apple/AppleMusicApi.kt` | Bearer reuse from `AppleMusicCanvas`; `get(path, query)` with 401 retry; `search(term, types, limit, storefront)`. |
| `app/.../data/apple/AppleMusicMappers.kt` | `amp-api` JSON → `Song`, `ShelfItem`, `DetailPage`, `SearchResult`. Apple ids kept as `apple:<kind>:<id>`. |
| `app/.../data/apple/AppleMusicHome.kt` | Web player `serverData` scrape → `List<HomeShelf>` (the BitChord-PC home). |
| `app/.../data/apple/AppleMusicCatalog.kt` | Album / playlist / artist detail via `amp-api` (`?include=tracks`), used by `openDetail`. |
| `app/.../data/apple/AppleMusicLibrary.kt` | **Phase 2** — account library via addon contract or addon token. |

### Edits

| File | Change |
| --- | --- |
| `AppleMusicCanvas.kt` | Expose `internal fun bearer()` and `internal fun catalogGet(url, bearer)` (no behaviour change). |
| `shared/.../HomeProvider.kt` | Add `APPLE`; `needsSignIn` false (anonymous works). |
| `MainViewModel.kt` | `loadHome`: Apple branch; `search`: Apple branch mapped to `SearchResult`; `openDetail`: Apple branch. |
| `SettingsHomeLibrary.kt` | Third provider option, "Apple Music (catalog)". |
| `SearchScreen` / search source chip | Apple selectable when the source axis offers it. |
| `DetailScreen` | No change: it already renders `DetailPage`. |
| `YtMusicRepository`/source matcher | Accept `apple:` ids as metadata-only rows; resolve playback by title/artist/album match. |
| strings | `apple_music_catalog`, `apple_search_source`, empty/error states. All 15 locales (or `translatable="false"`). |

## Phases (each lands compiling + tested)

### Phase 0 — API seam (no UI)
- Add `AppleMusicApi` over `AppleMusicCanvas`'s bearer/get.
- `AppleMusicApi.search(term, types, limit)` returns parsed JSON.
- Tests: token cache/expiry parsing is pure; search URL/params; JSON → `SearchResult` mapping.

### Phase 1 — Search (the user's explicit must)
- `AppleMusicMappers.searchResults(json)` → songs/albums/artists/playlists.
- Wire `MainViewModel.search` to an Apple source when the search axis is Apple.
- Playback: resolve each Apple song to a playable source at play time.
- No account needed.

### Phase 2 — Catalog home (BitChord-PC parity)
- `AppleMusicHome.shelves()`: GET `https://music.apple.com/<sf>/browse` (or
  `/<sf>`), extract the `serverData` JSON, walk `sections` → `shelf` → `lockups`
  to `HomeShelf` (port `src/apple.rs`: `server_data`, `sections`, `shelf`,
  `lockup`, `poster`, `fill`).
- `HomeProvider.APPLE` + settings option; `MainViewModel.loadHome` Apple branch.
- Artwork via `fill(template, w, h)`.

### Phase 3 — Detail pages
- Album / playlist / artist from `amp-api` (`/albums/{id}?include=tracks`,
  `/playlists/{id}?include=tracks`, `/artists/{id}?include=albums`).
- Map to `DetailPage`; tapped song resolves through the source matcher.

### Phase 4 — Account library (optional, "if easy")
Two routes; pick by what the exclusive addon exposes:
1. **Addon contract** (Apple-Auris): port `docs/APPLE_LIBRARY_ENDPOINT.md` reader
   (`/library/playlists|albums|songs`, paged) + `AppleLibraryScreen`. No direct
   Apple call; the addon owns the account. Most robust, least code.
2. **Addon token**: if the addon exposes a `music-user-token` in its settings,
   call `amp-api` `/v1/me/library/...` directly with
   `Media-User-Token: <token>` (plus the web bearer). This gives liked songs,
   playlists and albums without an app sign-in flow.
- A real MusicKit sign-in is **not** in scope: it needs an Apple developer token,
  a MusicKit JS/Android flow and a user token — too much for "if easy".

### Phase 5 — Polish
- Empty/error states ("Apple Music is unreachable", "No playable source").
- Storefront from the device locale (already in `AppleMusicCanvas.storefront`).
- Rate-limit/backoff: reuse `TOKEN_RETRY_MS`; cache home for the session.
- Localisation strings.

## Risks / rules

- **Never claim Apple audio playable.** DRM. Rows resolve through existing
  sources; else "No playable source".
- **No silent substitution** when an Apple lookup misses (the Apple-Auris fork's
  own rule).
- **No credentials in public payloads / logs**; bearer is scraped anonymously
  and never sent anywhere but `amp-api`.
- Web-player HTML/JS changes break scraping — the existing 401-reject and
  retry-after already handle rotation; keep them.
- `amp-api` is undocumented and may change; isolate it in `AppleMusicApi` so a
  break is one file.
- Locale parity: adding app strings requires all 15 locale files (or
  `translatable="false"`).

## Verification

- Unit: token JWT `exp` parsing; search query building; JSON mapping for each
  type; home `serverData` → shelves on a captured fixture.
- Compile: `:app:compileDevDebugKotlin`, `:desktopApp:compileKotlin`.
- Device (user): Settings → Home provider → Apple Music; Home shows Apple
  shelves; Search → Apple returns results; tapping a track either plays through
  the matched source or says no source; Apple error states are explicit.

## Reference commit

BitChord-PC: **`9b0cc55`** (`src/apple.rs`, 1447 lines) — token scrape
(`token`, `script_paths`, `jwt_candidates`, `jwt_expiry`, `is_web_player_token`,
`reject`), `api`, `shelves`, `collection`, `artist`, `song_motion`,
`album_motion`, `album_description`. Port the token/JWT/`serverData` logic; skip
the Rust-specific reqwest/`Motion` plumbing.