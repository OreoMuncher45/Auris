package com.music.bitchord.data.sources.backup

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.mornye.SflxCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export and import of the user's add-ons as one password-locked file.
 *
 * The file carries addon and custom-module sources (address, name, on/off,
 * order, and the keys typed into their settings), every installed extension's
 * package and settings, and the extensions' signed-session records. That's
 * everything a reinstall loses, so nothing has to be added by hand again.
 *
 * It complements, rather than overlaps, the settings backup in
 * [com.music.bitchord.data.stats.Backup]: that file leaves credentials out on
 * purpose, and this one exists to carry them, which is why it is always
 * encrypted.
 *
 * Threading: reading the registry is quick and safe anywhere, while disk and
 * content-resolver I/O run on [Dispatchers.IO] and key derivation and
 * encryption on [Dispatchers.Default]. Callers can launch these from the main
 * thread.
 *
 * Logging records counts and failure reasons only: never the password, an
 * address, a setting or a file's contents.
 */
object AddonBackup {

    private const val TAG = "BitChord"

    const val EXTENSION = "aurisaddons"
    const val MIME_TYPE = "application/octet-stream"

    /** A file this size is not a backup. Refused before it is read into memory. */
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /** Shortest password export accepts. Import takes whatever the file was locked with. */
    const val MIN_PASSWORD_LENGTH = 8

    /** What an export will contain, for the password dialog's description. */
    data class Summary(val addons: Int, val extensions: Int)

    /** An unlocked backup, ready to preview and apply. */
    class Unlocked internal constructor(
        val contents: AddonBackupContents,
        /**
         * Session records in the file that this device doesn't have yet. An
         * import that would only restore these is still worth running.
         */
        val missingSessions: Int = 0,
    ) {
        val exportedAt: Long get() = contents.index.exportedAt
        override fun toString() = "Unlocked($contents)"
    }

    /** What an import did, for the row's status line. */
    data class Result(val imported: Int, val skipped: Int, val failed: Int)

    fun suggestedName(now: Date = Date()): String =
        "auris-addons-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now)}.$EXTENSION"

    fun summary(): Summary = Summary(
        addons = SourceRegistry.configs.value.count { it.kind in AddonBackupMerge.BACKED_UP_KINDS },
        extensions = SflxCatalog.installed.value.size,
    )

    /** Builds the backup in memory. Reads the extension store, so call it off the main thread. */
    internal fun collect(appVersion: String, now: Long = System.currentTimeMillis()): AddonBackupContents {
        val configs = SourceRegistry.configs.value
        val sources = configs.filter { it.kind in AddonBackupMerge.BACKED_UP_KINDS }
            .map(AddonBackupMerge::backedUp)
        val rows = SourceRegistry.extensions()
        val installed = SflxCatalog.installed.value
        // In the order they're tried, then any package that has no source row.
        val ordered = rows.mapNotNull { row -> installed.firstOrNull { SflxCatalog.configIdOf(it.id) == row.id } } +
            installed.filter { pkg -> rows.none { it.id == SflxCatalog.configIdOf(pkg.id) } }
        val extensions = ordered.map { pkg ->
            BackedUpExtension(
                id = pkg.id,
                displayName = pkg.manifest.displayName,
                version = pkg.manifest.version,
                enabled = rows.firstOrNull { it.id == SflxCatalog.configIdOf(pkg.id) }?.enabled,
            )
        }
        return AddonBackupContents(
            index = AddonBackupIndex(
                exportedAt = now,
                appVersion = appVersion,
                sources = sources,
                extensions = extensions,
            ),
            extensionFiles = SflxCatalog.backupPackages(),
            sessionFiles = SflxCatalog.backupSessions(),
        )
    }

    /**
     * Writes a backup locked with [password] to [target], a document the user
     * picked. Clears [password] before returning, whatever happens.
     */
    suspend fun export(context: Context, target: Uri, password: CharArray, appVersion: String): kotlin.Result<Summary> =
        try {
            runCatching {
                val contents = withContext(Dispatchers.IO) { collect(appVersion) }
                val sealed = withContext(Dispatchers.Default) {
                    AddonBackupCrypto.seal(AddonBackupArchive.write(contents), password)
                }
                // Never write a file that read() would refuse: an export has to come back.
                if (sealed.size > MAX_FILE_BYTES) throw AddonBackupException(AddonBackupFailure.TOO_LARGE)
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(target, "wt")
                        ?: error("could not open the destination")
                    stream.use { it.write(sealed) }
                }
                Summary(contents.index.sources.size, contents.extensionFiles.size).also {
                    Log.d(TAG, "add-ons exported: ${it.addons} addons, ${it.extensions} extensions")
                }
            }.onFailure { Log.w(TAG, "add-ons export failed: ${it.javaClass.simpleName}") }
        } finally {
            password.fill('\u0000')
        }

    /** Reads [source] into memory without decrypting it, refusing anything that's clearly not a backup. */
    suspend fun read(context: Context, source: Uri): kotlin.Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(source)?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE_BYTES) throw AddonBackupException(AddonBackupFailure.TOO_LARGE)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: error("could not open the file")
            if (!AddonBackupCrypto.looksLikeBackup(bytes)) {
                // Distinguishes "not ours" from "ours but newer" for the message.
                AddonBackupCrypto.open(bytes, CharArray(0))
            }
            bytes
        }
    }

    /** Decrypts and unpacks [sealed]. Clears [password] before returning. */
    suspend fun unlock(sealed: ByteArray, password: CharArray): kotlin.Result<Unlocked> =
        try {
            withContext(Dispatchers.Default) {
                runCatching {
                    val contents = AddonBackupArchive.read(AddonBackupCrypto.open(sealed, password))
                    val present = withContext(Dispatchers.IO) { SflxCatalog.sessionNames() }
                    Unlocked(contents, missingSessions = contents.sessionFiles.keys.count { it !in present })
                }
                    .onFailure { Log.w(TAG, "add-ons unlock failed: ${reasonOf(it)}") }
            }
        } finally {
            password.fill('\u0000')
        }

    fun preview(unlocked: Unlocked): List<AddonPreviewItem> =
        AddonBackupMerge.preview(
            unlocked.contents,
            SourceRegistry.configs.value,
            SflxCatalog.installed.value.map { it.id }.toSet(),
        )

    /**
     * Applies [unlocked] to this device. New rows and packages take effect at
     * once; no restart is needed.
     *
     * Package and session files are written on [Dispatchers.IO]. Source-list
     * changes run on the main thread, where every other edit of the list
     * happens, so an import can't race a toggle on the screen.
     */
    suspend fun apply(unlocked: Unlocked, mode: AddonMergeMode): Result {
        val contents = unlocked.contents
        val plan = withContext(Dispatchers.IO) {
            AddonBackupMerge.plan(
                contents = contents,
                current = SourceRegistry.configs.value,
                installedExtensions = SflxCatalog.installed.value.map { it.id }.toSet(),
                existingSessions = SflxCatalog.sessionNames(),
                mode = mode,
            )
        }
        withContext(Dispatchers.Main) {
            plan.addSources.forEach(SourceRegistry::add)
            plan.replaceSources.forEach(SourceRegistry::update)
        }
        var sessionFailures = 0
        var packageFailures = 0
        val restored = withContext(Dispatchers.IO) {
            // Sessions first, so a package written below builds its session client from the restored record.
            sessionFailures = SflxCatalog.restoreSessions(
                plan.sessions.mapNotNull { name -> contents.sessionFiles[name]?.let { name to it } }.toMap(),
            )
            plan.extensions.filter { id ->
                val files = contents.extensionFiles[id] ?: return@filter false
                SflxCatalog.restorePackage(id, files).isSuccess.also { if (!it) packageFailures++ }
            }
        }
        // Disk reads for the refresh happen here, off the main thread; only publishing runs on Main.
        val snapshot = withContext(Dispatchers.IO) { SflxCatalog.snapshotAfterRestore() }
        withContext(Dispatchers.Main) {
            snapshot?.let(SflxCatalog::publishRestore)
            // The rows exist now. Apply the file's on/off state and order to what was restored.
            restored.forEach { id ->
                plan.extensionEnabled[id]?.let { on -> SourceRegistry.setEnabled(SflxCatalog.configIdOf(id), on) }
            }
            if (restored.isNotEmpty()) {
                val fileOrder = contents.index.extensions.map { SflxCatalog.configIdOf(it.id) }
                val current = SourceRegistry.extensions().map { it.id }
                SourceRegistry.reorderExtensions(fileOrder.filter { it in current } + current.filterNot { it in fileOrder })
            }
        }
        // `imported` counts sources and packages only, so only package failures come off it.
        // Session failures still show in the failure total.
        val failed = packageFailures + sessionFailures
        return Result(imported = plan.imported - packageFailures, skipped = plan.skipped, failed = failed).also {
            Log.d(TAG, "add-ons imported: ${it.imported}, skipped ${it.skipped}, failed ${it.failed}")
        }
    }

    /** The failure reason for a thrown error, or null for an I/O problem. */
    fun reasonOf(error: Throwable): AddonBackupFailure? =
        generateSequence(error) { it.cause }.filterIsInstance<AddonBackupException>().firstOrNull()?.reason
}
