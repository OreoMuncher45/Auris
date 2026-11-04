package com.music.auris.desktop

/** What this build is made of, and on what terms. */
internal data class AurisLicense(
    val name: String,
    val license: String,
    val url: String,
    val note: String = "",
)

/** Grouped the way someone checking a licence actually reads. */
internal data class AurisLicenseGroup(
    val title: String,
    val entries: List<AurisLicense>,
)

internal object AurisLicenses {

    val groups: List<AurisLicenseGroup> = listOf(
        AurisLicenseGroup(
            "This application",
            listOf(
                AurisLicense(
                    "Auris",
                    "GPL-3.0-or-later",
                    "https://www.gnu.org/licenses/gpl-3.0.html",
                    "Your music, your way.",
                ),
            ),
        ),
        AurisLicenseGroup(
            "Audio",
            listOf(
                AurisLicense(
                    "FFmpeg",
                    "LGPL-2.1-or-later",
                    "https://ffmpeg.org",
                    "Decoding, demuxing and resampling. The LGPL build is used " +
                        "deliberately; the GPL-enabled variant is not shipped.",
                ),
                AurisLicense(
                    "JavaCPP / JavaCPP Presets",
                    "Apache-2.0",
                    "https://github.com/bytedeco/javacpp",
                    "The bridge from the JVM to FFmpeg.",
                ),
                AurisLicense(
                    "OpenJFX",
                    "GPL-2.0-with-classpath-exception",
                    "https://openjfx.io",
                    "Video playback for animated cover art. Audio no longer goes through it.",
                ),
            ),
        ),
        AurisLicenseGroup(
            "Application framework",
            listOf(
                AurisLicense("Kotlin and kotlinx", "Apache-2.0", "https://kotlinlang.org"),
                AurisLicense(
                    "Compose Multiplatform",
                    "Apache-2.0",
                    "https://www.jetbrains.com/lp/compose-multiplatform/",
                    "The user interface, shared with the Android application.",
                ),
                AurisLicense("Ktor", "Apache-2.0", "https://ktor.io"),
                AurisLicense(
                    "Haze",
                    "Apache-2.0",
                    "https://github.com/chrisbanes/haze",
                    "The backdrop blur behind the floating bars, as on Android.",
                ),
                AurisLicense("SLF4J", "MIT", "https://www.slf4j.org"),
            ),
        ),
        AurisLicenseGroup(
            "Sources and integration",
            listOf(
                AurisLicense(
                    "NewPipeExtractor",
                    "GPL-3.0-or-later",
                    "https://github.com/TeamNewPipe/NewPipeExtractor",
                    "Stream URL deobfuscation, the same component the Android build uses.",
                ),
                AurisLicense(
                    "GraalVM JavaScript",
                    "UPL-1.0",
                    "https://www.graalvm.org",
                    "Runs source modules in a sandbox with no access to host classes.",
                ),
                AurisLicense(
                    "dbus-java",
                    "MIT",
                    "https://github.com/hypfvieh/dbus-java",
                    "MPRIS media controls and the system tray icon and menu.",
                ),
                AurisLicense(
                    "junixsocket",
                    "Apache-2.0",
                    "https://github.com/kohlschutter/junixsocket",
                    "Unix domain sockets for the session bus.",
                ),
            ),
        ),
    )
}
