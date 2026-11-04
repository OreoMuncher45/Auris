import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** What the packages, the launcher and the settings footer all say. */
val appVersion: String = providers.gradleProperty("auris.version").orNull
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: "1.8-beta1"

/** Which platform this build is *for*, which is the host unless told otherwise. */
val hostIsWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val hostIsLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
val targetOs: String = (providers.gradleProperty("auris.target").orNull ?: when {
    hostIsWindows -> "windows"
    hostIsLinux -> "linux"
    else -> error("Auris desktop supports Linux and Windows only")
}).lowercase()

// Windows installer metadata requires MAJOR.MINOR.BUILD even though the app's public version is
// intentionally displayed without a patch number (1.7 rather than 1.7.0).
// The build number is the desktop version code; it also keeps "-beta1" out of the numeric installer version.
val desktopVersionCode = 29
val nativePackageVersion = appVersion.substringBefore('-').split('.').take(2).joinToString(".") + ".$desktopVersionCode"

/**
 * Which processor architecture this build is for.
 *
 * The host's, unless the build says otherwise. A Linux build on an ARM machine used to ask for
 * the x64 renderer and then fail to start, because the renderer it shipped was for another
 * processor; the architecture is now part of what the build picks, so the same tree builds and
 * runs on both.
 */
val targetArch: String = (providers.gradleProperty("auris.arch").orNull ?: when {
    System.getProperty("os.arch").contains("aarch64", ignoreCase = true) -> "arm64"
    else -> "x64"
}).lowercase()

// FFmpeg decodes audio; see AurisAudioDecoder.
val javacppVersion = "1.5.12"
val ffmpegVersion = "7.1.1-$javacppVersion"
val nativeClassifier = when (targetOs) {
    "windows" -> "windows-x86_64"
    "linux" -> if (targetArch == "arm64") "linux-arm64" else "linux-x86_64"
    else -> error("Auris desktop supports Linux and Windows only")
}
private fun localProperty(name: String): String = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv(name).orEmpty().trim()

val desktopModuleIndexUrl = localProperty("MODULE_INDEX_URL")

/**
 * Whether this build must have the native libraries.
 *
 * A build that quietly drops the Automix analyser, the Windows frame bridge and the media-key
 * library is a build that ships a broken app: the analyser is what the cross-section and the
 * auto-mix need, the frame bridge is what makes the window behave like a Windows window, and the
 * media-key library is what puts the track in the volume flyout. A development build on a
 * machine without the toolchain has no way to run them anyway, so it carries on without them and
 * says so; a release build stops instead.
 */
val requireNative = providers.gradleProperty("auris.requireNative").orNull?.toBoolean() ?: false

// Last.fm signs every request with these, so signing in from inside the app
// needs them at hand. Supplied locally and never committed, as on Android.
val listenTogetherServer = localProperty("LISTEN_TOGETHER_SERVER")
val lastfmApiKey = localProperty("LASTFM_API_KEY")
val lastfmSecret = localProperty("LASTFM_SECRET")

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    // Already on the classpath through the NewPipe extractor.
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.zxing:core:3.5.3")

    implementation(project(":shared"))
    implementation(project(":sharedUi"))
    // BotGuard, for the PoTokens YouTube's web clients need: the phone runs it in an Android
    // WebView, the desktop in JavaFX's (WebKit). Per-platform jars carry the natives.
    val javafxClassifier = if (targetOs == "windows") "win" else "linux"
    listOf("base", "graphics", "controls", "media", "web").forEach { module ->
        implementation("org.openjfx:javafx-$module:21.0.10:$javafxClassifier")
    }

    // The shared player loads artwork through Coil, as the phone does.
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Automix runs the same two models the Android build does.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")

    // Decoding, resampling and demuxing for the audio engine.
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:$nativeClassifier")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$nativeClassifier")

    // The same backdrop blur the Android build uses for its floating bars.
    // On Windows, 1.7.2 keeps this Compose Desktop 1.10.3 app on the matching UI/text/animation
    // runtime. Preserve Linux's existing dependency graph exactly as it was.
    val desktopHazeVersion = if (targetOs == "windows") "1.7.2" else "1.7.3"
    implementation("dev.chrisbanes.haze:haze:$desktopHazeVersion")
    implementation("dev.chrisbanes.haze:haze-materials:$desktopHazeVersion")

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation(compose.materialIconsExtended)
    // The Skiko runtime rides in on this, and Skiko is per-platform.
    implementation(
        when {
            targetOs == "windows" -> compose.desktop.windows_x64
            targetArch == "arm64" -> compose.desktop.linux_arm64
            else -> compose.desktop.linux_x64
        },
    )

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    // Listen Together speaks over a WebSocket; the Discord gateway already uses the same engine.
    implementation("io.ktor:ktor-client-websockets:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // Browsers keep their cookies in SQLite, and importing a session from one is how this app signs
    // in — see DesktopBrowserCookies.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Chromium protects its Windows master key with DPAPI. JNA's platform helpers let the app
    // unwrap that key in the same Windows user session without invoking a shell or exposing it.
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    // Linux desktop media controls use the standard session-bus MPRIS interfaces.
    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-junixsocket:5.2.0")

    // Same URL deobfuscation helper used by Android's StreamResolver.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    // JVM equivalent of Android's QuickJS module host.
    implementation("org.graalvm.polyglot:polyglot:24.1.2")
    implementation("org.graalvm.polyglot:js:24.1.2")
    implementation("org.graalvm.js:js-language:24.1.2")

    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

/** Builds the Automix analyser from the same C++ the Android app compiles. */
// Per target, because both write the library under the same name and a Windows cross-build
// otherwise left a `.dll` where the next Linux run looked for its `.so`.
val analysisNativeDir = layout.buildDirectory.dir("native/$targetOs")

/** The tiny Windows frame bridge is independent of the optional analyser and SMTC libraries. */
val windowNativeDir = layout.buildDirectory.dir("native-window/$targetOs")

/**
 * Whether this build is producing the analyser for a platform that is not the one running the
 * build.
 */
val crossBuildingForWindows = targetOs == "windows" && !hostIsWindows

/**
 * An executable's absolute path, from the PATH this build was invoked with.
 *
 * Resolved here rather than left to the child process. Gradle runs external
 * commands from the daemon, whose own PATH is whatever it was started with, so
 * a daemon reused from a shell that had no cmake cannot find the one the
 * current shell does — it fails with a bare "No such file or directory" while
 * `cmake --version` works fine in the terminal beside it.
 */
/**
 * Whether a tool on the PATH can actually be started.
 *
 * A PATH entry can be a wrapper script whose own interpreter is gone; it is a file, it is
 * executable, and it fails only at exec time — which surfaces as a build failure rather than as the
 * missing-tool path this build already handles.
 */
fun runs(tool: File): Boolean = runCatching {
    val process = ProcessBuilder(tool.absolutePath, "--version")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        false
    } else {
        process.exitValue() == 0
    }
}.getOrDefault(false)

fun findOnPath(tool: String): File? {
    // Windows keeps the extension on the file and off the command line, so a bare name matches
    // nothing on disk: PATHEXT is the list a shell would have tried.
    val names = if (!hostIsWindows) {
        listOf(tool)
    } else {
        val extensions = System.getenv("PATHEXT").orEmpty()
            .ifBlank { ".COM;.EXE;.BAT;.CMD" }
            .split(';')
            .filter(String::isNotBlank)
        listOf(tool) + extensions.map { tool + it.lowercase() }
    }
    return System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter(String::isNotBlank)
        .flatMap { directory -> names.map { File(directory, it) } }
        // A PATHEXT match is executable by definition, and Windows' own canExecute is unreliable.
        .firstOrNull { it.isFile && (hostIsWindows || it.canExecute()) && runs(it) }
}

fun cmakeBinary(): String = findOnPath("cmake")?.absolutePath ?: "cmake"

/**
 * The cross compiler for a Windows build made on another platform.
 *
 * The POSIX threading model where the toolchain offers one: the win32 model ships no <mutex>
 * and no <thread>, and the Windows libraries here use both. Debian's alternatives point at the
 * win32 one by default, so the bare name is the wrong compiler there.
 */
fun crossCompilerName(): String =
    if (findOnPath("x86_64-w64-mingw32-g++-posix") != null) {
        "x86_64-w64-mingw32-g++-posix"
    } else {
        "x86_64-w64-mingw32-g++"
    }

val buildAnalysisNative by tasks.registering {
    val cmakeSource = project.file("native")
    val outputDir = analysisNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(rootProject.file("native/analyzer"))
    inputs.dir(rootProject.file("app/src/main/cpp/jni"))
    inputs.dir(project.file("native"))
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        val cmake = findOnPath("cmake")
        val compilerName = if (crossing) crossCompilerName() else "g++"
        val compiler = targetOs != "windows" || findOnPath(compilerName) != null
        val ninja = targetOs != "windows" || findOnPath("ninja") != null
        val available = cmake != null && compiler && ninja
        if (!available) {
            val hint = buildString {
                append("Auris desktop: ")
                if (cmake == null) {
                    append("cmake is not on PATH")
                } else {
                    append("$compilerName or Ninja not found")
                }
                append(" — the Automix analyser and the media-key library cannot be built.")
                if (requireNative) {
                    append(" Install the toolchain and rebuild; a release build must not ship without them.")
                } else {
                    append(
                        " Continuing without them. If the toolchain is installed, a Gradle daemon " +
                            "started before it was on PATH may be in use: ./gradlew --stop",
                    )
                }
            }
            if (requireNative) throw GradleException(hint)
            logger.lifecycle(hint)
        }
        available
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = if (targetOs == "windows") {
            findOnPath(if (crossing) crossCompilerName() else "g++")
                ?: error("the Windows C++ compiler disappeared while building the analyser")
        } else {
            null
        }
        val ninja = if (targetOs == "windows") {
            findOnPath("ninja") ?: error("Ninja disappeared while building the analyser")
        } else {
            null
        }
        providers.exec {
            commandLine(
                buildList {
                    addAll(listOf(cmakeBinary(), "-S", cmakeSource.absolutePath, "-B", outputDir.absolutePath))
                    if (targetOs == "windows") {
                        // The host may not have Visual Studio/nmake. Select the MinGW toolchain we
                        // checked above and discard any generator cached by an earlier configure.
                        addAll(
                            listOf(
                                "--fresh",
                                "-G", "Ninja",
                                "-DCMAKE_MAKE_PROGRAM=${ninja!!.absolutePath}",
                                "-DCMAKE_CXX_COMPILER=${compiler!!.absolutePath}",
                            ),
                        )
                    }
                    add("-DCMAKE_BUILD_TYPE=Release")
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "--config", "Release", "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

/**
 * Builds the Win32/DWM frame bridge even when the much larger analyser build is skipped during a
 * desktop UI run. It is intentionally absent from Linux builds, whose window manager remains the
 * sole owner of decoration and behaviour.
 */
val buildWindowNative by tasks.registering {
    val cmakeSource = project.file("native/window")
    val outputDir = windowNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(cmakeSource)
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        if (targetOs != "windows") return@onlyIf false
        val cmake = findOnPath("cmake")
        val compiler = findOnPath(if (crossing) crossCompilerName() else "g++")
        val ninja = findOnPath("ninja")
        val available = cmake != null && compiler != null && ninja != null
        if (!available) {
            val hint = "Auris desktop: cmake, Ninja, or a MinGW C++ compiler is unavailable — " +
                "the Windows build falls back to Compose's frameless window behaviour."
            if (requireNative) {
                throw GradleException(
                    "$hint A release build must have the frame bridge; install the toolchain and rebuild.",
                )
            }
            logger.lifecycle(hint)
        }
        available
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = findOnPath(if (crossing) crossCompilerName() else "g++")
            ?: error("MinGW C++ compiler disappeared while building the Windows frame")
        val ninja = findOnPath("ninja")
            ?: error("Ninja disappeared while building the Windows frame")
        providers.exec {
            commandLine(
                buildList {
                    addAll(
                        listOf(
                            cmakeBinary(),
                            "-S", cmakeSource.absolutePath,
                            "-B", outputDir.absolutePath,
                            "-G", "Ninja",
                            "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
                            "-DCMAKE_CXX_COMPILER=${compiler.absolutePath}",
                            "-DCMAKE_BUILD_TYPE=Release",
                        ),
                    )
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

/**
 * Checks that the native libraries the app loads by name are actually there.
 *
 * The analyser, the Windows frame bridge and the Windows media-key library are each loaded with a
 * name and nothing else: a missing one is a crash in the middle of the app — no cross-section, no
 * snap layouts, no track in the volume flyout — rather than a build error. A release build finds
 * out here, before the packages are cut, instead of a user finding out at runtime.
 */
val verifyNativeLibraries by tasks.registering {
    group = "verification"
    description = "Fails when a native library the app loads by name is missing."
    dependsOn(buildAnalysisNative, buildWindowNative)
    doLast {
        val libraryExtension = when (targetOs) {
            "windows" -> "dll"
            "linux" -> "so"
            else -> "dylib"
        }
        val required = buildList {
            add(
                File(
                    analysisNativeDir.get().asFile,
                    if (targetOs == "windows") "auris_analysis.dll" else "libauris_analysis.$libraryExtension",
                ),
            )
            if (targetOs == "windows") {
                add(File(windowNativeDir.get().asFile, "auris_window.dll"))
                // The media keys are the one library that a build can legitimately leave out: the
                // MinGW headers that carry the WinRT ABI are recent, and the app runs without the
                // library — it only loses the volume-flyout card and the media keys. A release
                // build does not accept that.
                if (requireNative) add(File(analysisNativeDir.get().asFile, "auris_smtc.dll"))
            }
        }
        val missing = required.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Auris desktop: the native libraries are missing — " +
                    missing.joinToString(", ") { it.name } +
                    ". The build needs cmake, Ninja and a C++ compiler; on Windows a MinGW-w64 " +
                    "toolchain whose headers carry the WinRT ABI (mingw-w64 11 or newer) builds " +
                    "the media-key library.",
            )
        }
        logger.lifecycle("Auris desktop: native libraries verified — ${required.joinToString(", ") { it.name }}")
    }
}

/**
 * The strings the app is written in, taken from where they are written.
 *
 * The phone keeps its wording in Android's resource folders; the shared UI keeps its own in
 * Compose's. The desktop used to keep a copy of both, which is a copy that goes stale the day a
 * translation lands on either side. These two tasks read the originals instead — the phone's
 * `res/values*` folders and the shared UI's `composeResources/values*` — and the catalogues are
 * read from them at runtime, so a string written once is the string both targets show.
 */
val phoneStringsDir = layout.buildDirectory.dir("generated/phone-strings")
val sharedStringsDir = layout.buildDirectory.dir("generated/shared-strings")

/** Turns `values-de/strings.xml` into `de.xml`, and `values/strings.xml` into `en.xml`. */
fun catalogueName(file: File): String {
    val parent = file.parentFile?.name.orEmpty()
    val language = if (parent.startsWith("values-")) parent.removePrefix("values-") else "en"
    return "$language.xml"
}

val syncPhoneStrings by tasks.registering {
    val source = rootProject.file("app/src/main/res")
    val output = phoneStringsDir
    inputs.dir(source)
    outputs.dir(output)
    doLast {
        val target = output.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        source.listFiles { file: File -> file.isDirectory && file.name.startsWith("values") }
            ?.forEach { folder ->
                val strings = File(folder, "strings.xml")
                if (!strings.isFile) return@forEach
                strings.copyTo(File(target, catalogueName(strings)), overwrite = true)
            }
    }
}

val syncSharedStrings by tasks.registering {
    // The shared catalogues are written into the resource tree by the phone-string sync, so that
    // task has to run first or the copy below reads a tree that may not be current.
    dependsOn(":sharedUi:syncPhoneStrings")
    val source = rootProject.file("sharedUi/src/commonMain/composeResources")
    val output = sharedStringsDir
    inputs.dir(source)
    outputs.dir(output)
    doLast {
        val target = output.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        source.listFiles { file: File -> file.isDirectory && file.name.startsWith("values") }
            ?.forEach { folder ->
                val strings = File(folder, "strings.xml")
                if (!strings.isFile) return@forEach
                strings.copyTo(File(target, catalogueName(strings)), overwrite = true)
            }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAnalysisNative, buildWindowNative, verifyNativeLibraries, syncPhoneStrings, syncSharedStrings)
    // The phone's own wording, and the shared UI's, under the names the catalogue reads.
    from(phoneStringsDir) { into("strings/phone") }
    from(sharedStringsDir) { into("strings/shared") }
    from(analysisNativeDir) {
        include("*.so", "*.dll", "*.dylib")
        into("native")
    }
    from(windowNativeDir) {
        include("*.dll")
        into("native")
    }
    // The Automix models, taken from the Android module rather than copied into this one.
    from(rootProject.file("app/src/main/assets")) {
        include("*.onnx")
        into("models")
    }
}

// Kotlin 2.4 no longer contributes this JVM target's compiler output to Gradle's plain `jar`
// task in this project. Compose's dev runner and the portable launcher both use that jar, so add
// the output explicitly instead of producing a resources-only application archive.
tasks.named<Jar>("jar") {
    val kotlinClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    dependsOn(kotlinClasses)
    from(kotlinClasses.flatMap { it.destinationDirectory })
}

/** A Windows build that can be assembled here, since an installer cannot be. */
val windowsPortableDir = layout.buildDirectory.dir("windows-portable")

val windowsPortableAssemble by tasks.registering {
    group = "distribution"
    description = "Lays out a Windows-runnable folder: application jars, Windows natives, launcher."
    dependsOn(tasks.named("jar"))
    val stage = windowsPortableDir.map { it.dir("Auris") }
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtime = configurations.named("runtimeClasspath")
    val version = appVersion
    val moduleIndex = desktopModuleIndexUrl
    val lastfmKey = lastfmApiKey
    val lastfmSharedSecret = lastfmSecret
    val mainClass = composeMainClass
    // Declared, not implied.
    inputs.file(appJar)
    inputs.files(runtime)
    inputs.property("version", version)
    inputs.property("moduleIndex", moduleIndex)
    inputs.property("lastfm", lastfmKey.isNotBlank())
    inputs.property("mainClass", mainClass)
    outputs.dir(stage)
    doLast {
        val target = stage.get().asFile
        val lib = File(target, "lib")
        lib.deleteRecursively()
        lib.mkdirs()

        // Named by coordinate rather than by file name.
        runtime.get().incoming.artifacts.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier
            val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
            val name = if (group.isNullOrBlank()) artifact.file.name else "$group.${artifact.file.name}"
            artifact.file.copyTo(File(lib, name), overwrite = true)
        }
        appJar.get().asFile.copyTo(File(lib, "auris-app.jar"), overwrite = true)

        // `lib\*` rather than a spelled-out classpath: the wildcard is what keeps this working when
        // a dependency is added, and the JVM is what expands it.
        File(target, "Auris.bat").writeText(
            buildString {
                appendLine("@echo off")
                appendLine("setlocal")
                appendLine("rem Auris, portable. Needs a Java 21+ runtime on PATH or in JAVA_HOME.")
                appendLine("set \"HERE=%~dp0\"")
                appendLine("set \"RUNNER=java\"")
                appendLine("if defined JAVA_HOME set \"RUNNER=%JAVA_HOME%\\bin\\java\"")
                append("\"%RUNNER%\" -Dauris.version=$version")
                if (moduleIndex.isNotBlank()) append(" -Dauris.module.index=$moduleIndex")
                if (lastfmKey.isNotBlank() && lastfmSharedSecret.isNotBlank()) {
                    append(" -Dauris.lastfm.key=$lastfmKey -Dauris.lastfm.secret=$lastfmSharedSecret")
                }
                appendLine(" -cp \"%HERE%lib\\*\" $mainClass %*")
                appendLine("endlocal")
            },
        )

        // What "it builds" is not the same as "it runs", and the difference is exactly what shipped
        // a broken archive.
        val jars = lib.listFiles { file: File -> file.name.endsWith(".jar") }.orEmpty()
        fun holds(entry: String) = jars.any { jar ->
            ZipFile(jar).use { zip -> zip.getEntry(entry) != null }
        }
        val problems = buildList {
            if (!holds("com/music/auris/desktop/MainKt.class")) {
                add("the Auris desktop entry point is missing")
            }
            if (!holds("androidx/compose/runtime/Composer.class")) {
                add("the Compose runtime is missing")
            }
            if (jars.none { it.name.contains("skiko-awt-runtime-windows") }) {
                add("the Windows Skiko renderer is missing")
            }
            if (jars.any { it.name.contains("skiko-awt-runtime-linux") }) {
                add("a Linux Skiko renderer is in a Windows archive")
            }
            if (!holds("org/bytedeco/ffmpeg/windows-x86_64/avformat.dll") &&
                jars.none { it.name.contains("ffmpeg") && it.name.contains("windows-x86_64") }
            ) {
                add("the Windows FFmpeg natives are missing")
            }
        }
        check(problems.isEmpty()) { "windowsPortable: " + problems.joinToString("; ") }

        val analyser = File(analysisNativeDir.get().asFile, "auris_analysis.dll").isFile
        logger.lifecycle(
            if (analyser) {
                "windowsPortable: verified — Compose runtime, Windows renderer, Windows FFmpeg, Automix analyser."
            } else {
                "windowsPortable: verified, but no auris_analysis.dll — Automix will not run in this archive. " +
                    "Rebuild with: nix-shell -p cmake pkgsCross.mingwW64.buildPackages.gcc"
            },
        )
    }
}

val windowsPortable by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the portable Windows folder for copying to a Windows machine."
    dependsOn(windowsPortableAssemble)
    from(windowsPortableDir)
    archiveFileName.set("Auris-$appVersion-windows-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    doLast { logger.lifecycle("windowsPortable: ${archiveFile.get().asFile.absolutePath}") }
}

val composeMainClass = "com.music.auris.desktop.MainKt"

compose.desktop {
    application {
        mainClass = composeMainClass
        jvmArgs("-Xmx512m")
        // Software rendering, for a machine with no GPU: a virtual desktop, a container, a
        // build agent. The renderer that normally wants a GL context draws through Skia's own
        // rasteriser instead, which is slower but works anywhere.
        if (providers.gradleProperty("auris.softwareRender").orNull?.toBoolean() == true) {
            jvmArgs("-Dskiko.renderApi=SOFTWARE")
        }
        jvmArgs("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=20000", "-XX:G1PeriodicGCSystemLoadThreshold=0")
        jvmArgs("-Dauris.version=$appVersion")
        // The window's own shape can be overrule from the command line, for a screen nobody
        // touches: -Pauris.sidebar=off puts the sidebar away for good.
        providers.gradleProperty("auris.sidebar").orNull?.let { sidebarChoice ->
            jvmArgs("-Dauris.sidebar=$sidebarChoice")
        }
        if (desktopModuleIndexUrl.isNotBlank()) {
            jvmArgs("-Dauris.module.index=$desktopModuleIndexUrl")
        }
        if (lastfmApiKey.isNotBlank() && lastfmSecret.isNotBlank()) {
            jvmArgs("-Dauris.lastfm.key=$lastfmApiKey", "-Dauris.lastfm.secret=$lastfmSecret")
        }
        if (listenTogetherServer.isNotBlank()) {
            jvmArgs("-Dauris.listentogether.server=$listenTogetherServer")
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("packaging/proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(
                TargetFormat.Exe,
                TargetFormat.Msi,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "Auris"
            packageVersion = nativePackageVersion
            description = if (targetOs == "windows") "Auris" else "Aesthetic YouTube Music client"
            vendor = "Auris contributors"
            copyright = "Copyright © 2026 Auris contributors"

            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.dynalink",
                // Without this the runtime image has only the ANSI code page, and every UTF-8
                // request — a song title, a playlist description, a lyrics file the app wrote
                // itself — comes back as replacement characters. A development run works because
                // the full JDK carries the charset tables; the packaged app did not.
                "jdk.charsets",
                // JavaFX WebEngine returns JavaScript objects through netscape.javascript.JSObject.
                // Without this module the slim production runtime crashes in twkExecuteScript,
                // while development runs work because the full JDK already contains it.
                "jdk.jsobject",
                "jdk.localedata",
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.zipfs",
            )

            linux {
                iconFile.set(project.file("packaging/icons/AppIcon.png"))
                menuGroup = "Audio"
                appCategory = "AudioVideo;Audio;Player"
                debMaintainer = "85984486+AbhiTheModder@users.noreply.github.com"
                rpmLicenseType = "GPL-3.0"
            }

            windows {
                iconFile.set(project.file("packaging/icons/AppIcon.ico"))
                upgradeUuid = "8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913"
                menuGroup = "Auris"
                shortcut = true
                dirChooser = false
                perUserInstall = true
            }
        }
    }

}
