package com.music.bitchord.playback.smart

import android.content.SharedPreferences
import java.io.File

/**
 * The one-time move from Auris's own Automix engine (automix-core-v2) onto
 * BitChord's, run on the first launch of a build that has BitChord's.
 *
 * What it throws away is what the new engine cannot read:
 *  - the analysis store ([RETIRED_ANALYSIS_DIRECTORY]), written in a format and
 *    with fields the new [TrackAnalysis] does not share — and holding up to a
 *    few thousand files the new store would otherwise only overwrite as each
 *    track happened to come round again;
 *  - the per-song Automix profiles ([RETIRED_PROFILE_DIRECTORY]), which belonged
 *    to the removed panel and have no reader at all now;
 *  - the `automix_engine` preference, which only ever named the removed engine.
 *
 * The shared settings stay, because they mean the same thing to both engines:
 * `crossfade_seconds` (the manual crossfade length in seconds),
 * `smart_fade_enabled` (Automix on or off), `automix_performance_mode` (the
 * same three [com.music.bitchord.data.settings.AutomixPerformanceMode] names)
 * and `smart_version_alignment` (version switching, not Automix at all).
 *
 * The directories are renamed out of the way on the calling thread — a rename
 * is one metadata write however many files are inside — and their contents
 * deleted by [deleteLater], so a few thousand small files never cost the first
 * frame. A rename that fails falls back to deleting in place, also off the
 * calling thread. A renamed directory a killed process left behind is deleted
 * on the next launch.
 *
 * The new engine's store is not under any retired name — see [analysisHome] —
 * so nothing here can reach it: a retirement that fails, or a done marker that
 * never reached the disk, only means the old directories are looked at again.
 */
object AutomixMigration {

    /** Set once the move has run; a fresh install sets it with nothing to move. */
    const val KEY_DONE = "automix_bitchord_engine_migrated"

    /** The removed engine switch. */
    const val KEY_RETIRED_ENGINE = "automix_engine"

    /** The old engine's analysis store. */
    const val RETIRED_ANALYSIS_DIRECTORY = "smart_analysis"

    /** Where the removed per-song Automix profiles lived. */
    const val RETIRED_PROFILE_DIRECTORY = "automix-profiles"

    /** The directories the old engine wrote under the app's files directory. */
    val RETIRED_DIRECTORIES = listOf(RETIRED_ANALYSIS_DIRECTORY, RETIRED_PROFILE_DIRECTORY)

    /**
     * Where the new engine's [AnalysisStore] lives, under the files directory.
     *
     * Its own directory rather than the old one's name: the two engines used
     * to share `smart_analysis`, so a retirement that did not finish let the
     * new store write into the directory the next launch would retire, and
     * that took the new results with it.
     */
    const val ANALYSIS_HOME = "automix"

    fun analysisHome(filesDir: File): File = File(filesDir, ANALYSIS_HOME)

    /** What a directory is renamed to before its contents are deleted, followed by a timestamp. */
    const val RETIRED_SUFFIX = ".retired-"

    /**
     * Runs the move if it has not run on this install. Returns whether it ran
     * to completion.
     */
    fun run(
        prefs: SharedPreferences,
        filesDir: File,
        deleteLater: (File) -> Unit = { dir -> Thread({ dir.deleteRecursively() }, "automix-migration").start() },
        rename: (File, File) -> Boolean = File::renameTo,
    ): Boolean {
        // Anything renamed aside on an earlier launch whose deletion the
        // process did not live to finish. Looked for on every launch, done or
        // not: a single listing of the files directory.
        filesDir.listFiles { file -> RETIRED_DIRECTORIES.any { file.name.startsWith("$it$RETIRED_SUFFIX") } }
            ?.forEach(deleteLater)
        if (prefs.getBoolean(KEY_DONE, false)) return false
        for (name in RETIRED_DIRECTORIES) {
            val directory = File(filesDir, name)
            if (!directory.exists()) continue
            val retired = File(filesDir, "$name$RETIRED_SUFFIX${System.nanoTime()}")
            deleteLater(if (rename(directory, retired)) retired else directory)
        }
        // Not done while an old directory is still in place: the next launch
        // looks again.
        if (RETIRED_DIRECTORIES.any { File(filesDir, it).exists() }) return false
        prefs.edit()
            .remove(KEY_RETIRED_ENGINE)
            .putBoolean(KEY_DONE, true)
            .apply()
        return true
    }
}
