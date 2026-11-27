import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Signing details, kept out of the repository in `keystore.properties`
 * (see keystore.properties.example). Absent on a fresh checkout, in which case
 * the release build still runs and simply comes out unsigned rather than
 * failing — only whoever holds the key can produce a shippable APK.
 */
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val lastfmApiKey: String = (
    localProps.getProperty("LASTFM_API_KEY")
        ?: System.getenv("LASTFM_API_KEY")
        ?: ""
    ).trim()
val lastfmSecret: String = (
    localProps.getProperty("LASTFM_SECRET")
        ?: System.getenv("LASTFM_SECRET")
        ?: ""
    ).trim()

/*
 * Where Listen Together's party server lives. Not a credential — it is a public
 * URL, and every device in a party has to be pointed at the same one — but it is
 * deployment-specific rather than a property of the source, which is what puts
 * it here beside the others instead of in a constant.
 *
 * Empty is a supported state, not a broken build: the field below is only the
 * *default* the address box on the Listen Together screen starts with, and
 * anything typed there wins and persists. So a fresh checkout without this line
 * builds and runs, and simply asks for an address the first time somebody opens
 * the screen. See ListenTogether.DEFAULT_SERVER.
 */
val listenTogetherServer: String = (
    localProps.getProperty("LISTEN_TOGETHER_SERVER")
        ?: System.getenv("LISTEN_TOGETHER_SERVER")
        ?: "https://bitchord-listen-together.onrender.com"
    ).trim().trimEnd('/')

/*
 * Bump this by hand before cutting each sideloaded test build ("beta2",
 * "beta3", ...) and blank it out before cutting the real release. Marks the
 * versionName below as a pre-release: AppUpdateChecker.isNewer() treats any
 * "-suffix" as older than a clean release of the same number, so testers
 * still get the update prompt once the matching tag is actually published.
 *
 * Applied to release builds as well as debug ones, and that is the whole
 * point of it. A sideloaded beta is a *release* build — signed with the real
 * key, installed over the real package — so leaving the marker off it is
 * exactly the case that strands a tester: their build calls itself 1.6.1,
 * the published 1.6.1 then matches it, isNewer() says no, and no prompt ever
 * comes. Blanking this line is the one step that turns a beta into a release,
 * so it is the one place to get right.
 */
val betaSuffix = ""

android {
    namespace = "com.music.bitchord"
    // InnerTubeX's AAR requires compiling against 37; targetSdk (runtime behaviour) stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.music.bitchord"
        // 26 keeps reach wide; real-time blur (RenderEffect) kicks in on API 31+,
        // Haze falls back to a translucent scrim below that.
        minSdk = 26
        targetSdk = 36
        versionCode = 134
        versionName = "3.0.29"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Strip symbol tables from our own CMake-built .so (ffmpeg/jamesdsp/
        // analysis/dsd/usb). The big prebuilt libs arrive already stripped.
        ndk {
            debugSymbolLevel = "none"
        }

        // Last.fm credentials are supplied locally and never committed.
        buildConfigField("String", "LASTFM_API_KEY", "\"${lastfmApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "LASTFM_SECRET", "\"${lastfmSecret.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField(
            "String",
            "LISTEN_TOGETHER_SERVER",
            "\"${listenTogetherServer.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // applicationId can only be overridden per flavor, not per build type, so a
    // dev/prod dimension exists purely to let both sit installed side by side
    // on the same device instead of the dev build overwriting the prod one.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationId = "com.dev.bitchord"
            resValue("string", "app_name", "Auris")
        }
        create("prod") {
            dimension = "env"
            // Matches defaultConfig — this is the package already shipped/installed.
        }
    }

    signingConfigs {
        // Both halves have to be there, not just the properties file: it *names*
        // the keystore rather than containing it, and both are gitignored
        // separately, so a checkout can easily end up with the one and not the
        // other. A signing config pointing at a keystore that is not on disk
        // fails the release build outright at validateSigningRelease — which is
        // exactly the failure the unsigned fallback above exists to avoid, so
        // the keystore has to be looked for rather than assumed.
        val store = signing.getProperty("storeFile")?.let { rootProject.file(it) }
        if (store != null && store.exists()) {
            create("release") {
                storeFile = store
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
        }
        release {
            // Carried here too — see [betaSuffix]. A sideloaded beta is a
            // release build, and it is the one that most needs the marker.
            if (betaSuffix.isNotEmpty()) versionNameSuffix = "-$betaSuffix"
            /*
             * On for what it does to speed, not size. Compose is written to be
             * run through R8 — without it every composable keeps the debug-era
             * shape the compiler emits, and the whole UI runs measurably slower.
             *
             * Nothing is renamed (-dontobfuscate), and every library that reaches
             * for classes by name — Rhino running YouTube's player JavaScript,
             * NewPipe, InnerTubeX, QuickJS, SMBJ and BouncyCastle, ONNX's JNI,
             * protobuf-lite, Ktor — is kept whole: see proguard-rules.pro. What R8
             * is left to optimise is Compose, Media3, coroutines and our own
             * code, which is where the time goes. Checked on a device through the
             * `benchmark` build type below before it ships.
             */
            isMinifyEnabled = true
            // Drop resources no code references (dead drawables, unused locale
            // variants of drawables, etc.) — pairs with R8 above.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null without a keystore to sign with: the build then produces
            // app-release-unsigned.apk instead of failing outright.
            signingConfig = signingConfigs.findByName("release")
        }
        /*
         * The release build, installable next to the dev and prod apps: same R8,
         * same non-debuggable runtime, signed with the debug key under its own
         * package so it never replaces either. For measuring startup the way
         * users get it and for checking that shrinking broke nothing — a debug
         * build is interpreted and verified at runtime and says little about
         * either. `./gradlew installDevBenchmark`.
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            // SMBJ's BouncyCastle and jspecify both ship this descriptor.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            // Compress .so inside the APK (extractNativeLibs=true) so the
            // download is far smaller; they are extracted once at install.
            // The big win is libonnxruntime.so (~27 MB stored -> ~10 MB).
            useLegacyPackaging = true
        }
    }
    androidResources {
        // Compress everything aapt would otherwise store raw (fonts, flow
        // videos). resources.arsc stays aligned/uncompressed
        // automatically; jniLibs are governed by packaging.jniLibs above.
        noCompress.clear()
        // Automix's models stay in src/main/assets (the desktop build reads
        // them from there) but are not packaged: the app downloads them on
        // first use, see AutomixModels. The rest is AGP's default pattern,
        // which setting this replaces. Ported from BitChord.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!*.onnx"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric needs merged resources and the manifest to inflate a
            // composable.
            isIncludeAndroidResources = true

            all {
                // LIVE_TESTS changes whether the Genius network test runs.
                // Track it as a task input so Gradle cannot reuse a cached
                // result when the environment changes.
                it.inputs.property("liveTests", System.getenv("LIVE_TESTS") ?: "")

                // Conscrypt, which Robolectric loads by JNI during sandbox
                // setup, publishes no linux-aarch64 build, so on this host the
                // interaction tests die with UnsatisfiedLinkError before
                // reaching an assertion. CI is x86_64 and runs them for real.
                if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) {
                    it.filter.excludeTestsMatching("com.music.bitchord.ui.apple.*InteractionTest")
                    it.filter.excludeTestsMatching("com.music.bitchord.data.HomeFeedCacheTest")
                    it.filter.excludeTestsMatching("com.music.bitchord.data.canvas.SpotifyTokenSessionTest")
                    // These playback/menu tests are Robolectric too. macOS on
                    // arm has a Conscrypt build and runs them; Linux on arm does not.
                    if (System.getProperty("os.name").startsWith("Linux")) {
                        it.filter.excludeTestsMatching("com.music.bitchord.playback.ResolvedLocalPlaylistDataSourceTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.menu.AnchoredMenuTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.menu.AnchoredItemMenuTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.menu.MenuHostTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.menu.AppSheetTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.menu.AppSheetFocusTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.menu.SheetHostTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.menu.DialogHostTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.player.PlayerDrawerTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.SeenWhenOnScreenTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.MenuOpenCostProbe")
                        it.filter.excludeTestsMatching("com.music.bitchord.SettingsOverDetailTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.SearchFoldOverPageTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.components.MediaDownloadButtonRecomposeTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.RootRecomposeOnPageAppendTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.SflxFetchContentTypeTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.data.sources.mornye.SflxFilesTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.playback.BorrowedSiblingReopenTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.playback.cenc.CencExtractorsFactoryTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.playback.cenc.CencFlacFileTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.playback.cenc.CencReviewCasesTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.player.PlayerPanelRotationTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.screens.SettingsScrollRestoreTest")
                        it.filter.excludeTestsMatching("com.music.bitchord.ui.screens.ArtistPageInteractionTest")
                    }
                }
                // MenuOpenCostProbe runs only with MENU_PROBE=1, and Gradle
                // doesn't track the environment: without this, a run that
                // skipped it stays up to date when the flag is turned on.
                it.inputs.property("menuProbe", System.getenv("MENU_PROBE") ?: "")
            }

            // Unit tests run against a stub android.jar whose methods throw
            // rather than return. That is the right default for anything whose
            // behaviour depends on the framework, and wrong for android.util.Log
            // — which [TrackLog] calls on every decision the source layer makes,
            // so a test of that layer fails on the logging rather than on the
            // logic it was written to check.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Lambdas as classes, not invokedynamic.
        //
        // Kotlin 2.3 compiles a lambda literal into an invokedynamic whose
        // call site carries every captured value as a stack argument. The
        // AppRootBox content lambda inside MainActivity.kt captures 252
        // values, so the call site has 255 arguments and dex has to encode
        // them as one invoke-direct/range — a register window of 256 words.
        // The window's size lives in one byte, 256 wraps to 0, and ART reads
        // that as "no arguments at all" and rejects the whole class:
        //
        //   VerifyError: Verifier rejected class com.music.bitchord.MainActivityKt
        //   [0xFFFFFFFF] invalid arg count (0) in invoke-direct/range
        //
        // A class-based lambda keeps the captured values in a constructor
        // (252 arguments) and passes the lambda's own parameters to invoke()
        // instead, so the widest call is 253 words and fits. The flag costs a
        // little indirection and buys back the crash.
        freeCompilerArgs.add("-Xlambdas=class")
    }
}

/*
 * Two guards on the compiled classes, run after every Kotlin compile of every
 * variant (dev and prod; debug, release and benchmark), so the shipping
 * prodRelease and the devDebug that pull-request CI builds are both checked.
 * The wiring is gradle/class-checks.gradle.kts, shared with :sharedUi and
 * :desktopApp.
 *
 * dex keeps the size of a call's register window in one byte, so a call
 * cannot pass more than 255 words. A constructor with 254 arguments needs
 * 255 of them — the object itself and the arguments — and is the most a
 * dex can carry. One argument more and the count wraps to zero, the class
 * is refused at launch, and nothing in the build says so. Kotlin does not
 * object: the limit is dex's, and dex is not the compiler. The lambdas that
 * capture the most are the ones to watch, and a capture set grows by one
 * every time a local is added to a big composable.
 *
 * The JVM's own limit is the other one a big composable runs into: 65,535
 * bytes of bytecode per method. A composable is one method however long its
 * source is, so it creeps up on an ordinary change and then the compiler
 * stops with a MethodTooLargeException (OVERHAUL_PLAN.md has one, in
 * BitChordApp). A method over 32,000 bytes — about half the limit, the target
 * issue #5 set — fails the build while there is still room to split it calmly.
 */
extra["classChecks"] = listOf("dev", "prod").flatMap { flavor ->
    listOf("debug", "release", "benchmark").map { type ->
        val variant = flavor + type.replaceFirstChar { it.uppercase() }
        "compile${variant.replaceFirstChar { it.uppercase() }}Kotlin" to "tmp/kotlin-classes/$variant"
    }
}.toMap()
apply(from = rootProject.file("gradle/class-checks.gradle.kts"))
// The names these checks have always had: the dev release classes.
tasks.register("checkConstructorWidths") {
    group = "verification"
    description = "Fails if a constructor in the dev release classes is wider than dex can call."
    dependsOn("checkConstructorWidthsDevReleaseKotlin")
}
tasks.register("checkMethodSizes") {
    group = "verification"
    description = "Fails if a method in the dev release classes is over 32,000 bytes."
    dependsOn("checkMethodSizesDevReleaseKotlin")
}

/*
 * NewPipeExtractor ships its own org.schabi.newpipe.extractor.utils.Utils, and
 * app/src/main/java carries a patched copy at the same package path (see that
 * file for why it exists). A debug build keeps project and library dex separate,
 * so the project copy simply wins at class-load time and the two coexist; a
 * release build merges every input into one dex set, where D8 rejects the
 * duplicate type outright ("Utils is defined multiple times"). So the library's
 * copy is stripped from its jar before it reaches dexing, leaving exactly one
 * definition of the class in the build.
 *
 * The artifact is resolved on its own and non-transitive purely to re-jar it;
 * the transitive dependencies it would otherwise have carried are declared by
 * hand in the dependencies block below, since dropping the module drops them too.
 */
val newPipeExtractorRaw: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies {
    newPipeExtractorRaw("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
}
val newPipeExtractorStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>(
    "stripNewPipeExtractorUtils"
) {
    archiveFileName.set("NewPipeExtractor-v0.26.3-noutils.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from(provider { newPipeExtractorRaw.map { zipTree(it) } }) {
        // The class itself, plus any nested or synthetic siblings the upstream
        // compiler emitted alongside it, so nothing from the jar's Utils survives.
        exclude("org/schabi/newpipe/extractor/utils/Utils.class")
        exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":sharedUi"))
    implementation(project(":audio:decent-usb-audio-driver"))

    // ---- Compose (Material 3) ----
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // Pinned above the BOM's 1.7.6: [IosOverscroll] uses OverscrollFactory,
    // which that version doesn't have. Newer foundation alongside the BOM's
    // older ui/material3 is a combination Compose supports deliberately —
    // foundation depends on ui, not the reverse — and this exact pairing was
    // already in effect (foundation was reaching 1.10.0 transitively through
    // the liquid-glass library before that dependency was removed).
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner: whether the app is on screen, for the open-app count.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Media playback: Media3 / ExoPlayer ----
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    // Audio is progressive, but Apple serves its motion artwork as HLS — this
    // is what lets the animated sleeve play it. See CanvasArtworkPlayer.
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    // Source modules hand back manifests rather than files, and which kind is
    // the backend's choice, not ours: the Tidal one served `.m3u8` until
    // September 2026 and `.mpd` after it, for the same track and the same
    // request. Without this artifact a DASH manifest is not merely unplayed —
    // DefaultMediaSourceFactory cannot build a source for it, falls back to
    // progressive, and the extractors try to sniff XML as audio
    // (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED). See withResolvedStreamType.
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
    // FFmpeg audio decoding for what the phone has no decoder of its own for:
    // ALAC on every phone, and AC-4, E-AC-3 / AC-3 (Dolby Atmos music),
    // TrueHD and DTS on phones without Dolby or DTS licensed in. Added as the
    // *last* audio renderer in silenceSkippingRenderers, so a platform decoder
    // still wins wherever one exists. No artifact: the extension's Java half is
    // vendored under app/src/main/java/androidx/media3/decoder/ffmpeg, and
    // libffmpegJNI.so under src/main/jniLibs is built from librempeg (the only
    // FFmpeg line with an AC-4 decoder) by native/ffmpeg/build.sh. It needs
    // media3-decoder, which media3-exoplayer already brings.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    // ---- Images: Coil 3 + Palette (dominant colors for the mesh gradient) ----
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ---- Frosted glass / progressive blur (Telegram-style bars) ----
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.3.1")

    // ---- QR encoding, for the party invite ----
    // `core` only: the `android-core`/`zxing-android-embedded` artifacts bring
    // a camera scanner and an Activity with it, and nothing here reads a code —
    // a party is joined by tapping somebody else's link or typing six
    // characters. This produces the bit matrix; the drawing is ours, in
    // [com.music.bitchord.ui.components.QrCode], so the result is styled like
    // the rest of the app rather than a stock black-and-white bitmap.
    implementation("com.google.zxing:core:3.5.3")

    // ---- Markdown rendering (release notes in the update dialog) ----
    // Pure Compose, not an AndroidView wrapper — needed so the text composes
    // correctly under the dialog's Haze blur.
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.20.0")
    implementation("com.halilibo.compose-richtext:richtext-commonmark:0.20.0")

    // ---- Innertube (YouTube Music) client: Ktor + kotlinx.serialization ----
    // Ktor and serialization are held at InnerTubeX's versions (below) so the
    // upgrade it forces is explicit rather than resolved behind our backs.
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // ---- Discord Rich Presence: the gateway is a WebSocket, so Ktor needs the plugin ----
    implementation("io.ktor:ktor-client-websockets:3.5.2")

    // ---- YouTube stream extraction: live-benchmarked client catalog + cipher tiers ----
    implementation("com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.0")

    // ---- Stream resolution: NewPipe solves YouTube's signature + `n` throttling ----
    // Pinned to v0.26.3, not the newer v0.26.4: v0.26.4's player-JS parser fails with
    // "Could not parse deobfuscation function" on the current player build, which blocks
    // WEB_REMIX's ciphered formats entirely. v0.26.3 solves the same signatures cleanly
    // against the same player JS — confirmed side by side against PixelMusic-ref, which
    // pins v0.26.3 and doesn't hit the parse failure.
    //
    // Consumed as a stripped jar rather than as the module, so its own
    // Utils.class does not reach dexing. See newPipeExtractorStripped above; the
    // transitive dependencies the module would have brought are listed here
    // because dropping its artifact drops them too. If the version changes,
    // re-derive this list with
    //   ./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath
    implementation(files(newPipeExtractorStripped))
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

    // ---- Auth/session storage ----
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ---- JS module execution: QuickJS VM for style source plugins ----
    // Held at InnerTubeX's version; the same VM runs QuickJsExecutor's module sources.
    implementation("io.github.dokar3:quickjs-kt-android:1.0.14")

    // ---- SMB file shares: pure-Java SMB2/3 client (listing + streaming) ----
    implementation("com.hierynomus:smbj:0.15.0")

    // ---- Automix: on-device beat/downbeat model (Beat This!, MIT-licensed) ----
    // The full android artifact, not onnxruntime-mobile: mobile only loads .ort
    // files, which would put an offline conversion step between the model and
    // the app for a saving that does not matter in a self-distributed APK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    // ---- Casting: Google Cast sender + the route discovery it sits on ----
    // The framework and the router only. media3-cast is left out on purpose:
    // its CastPlayer hands the receiver whatever URI a MediaItem carries, and
    // ours are `bitchord://` addresses that only the service's resolver can
    // turn into a real stream — see [com.music.bitchord.playback.cast.CastPlayback].
    implementation("com.google.android.gms:play-services-cast-framework:22.2.0")
    implementation("androidx.mediarouter:mediarouter:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for the signed-session JVM tests; the stub android.jar
    // silently discards fields, which is not a test of the request contract.
    testImplementation("org.json:json:20240303")
    // A real HTTP server for the addon tests. The addon protocol is entirely
    // "what does this app send, and what does it do with what comes back", and
    // a hand-rolled fake of the client would be a test of the fake. Pinned to
    // the OkHttp version already on the runtime classpath.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    // Compose interaction tests. Every other test in this module is pure JVM
    // logic, which cannot render a composable at all — so a UI whose callbacks
    // were dead shipped twice before anything noticed. Robolectric runs the
    // real tree on the JVM; it needs Conscrypt's native lib, which is x86_64
    // only, so these are skipped on aarch64 hosts (see testOptions).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // A self-signed certificate for the loopback server in
    // SflxDecryptedPlaybackTest: the app's network policy refuses cleartext,
    // and the test should not loosen it. Pinned to the runtime's OkHttp.
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.3.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

/*
 * A debug APK lands on the device uncompiled — `dumpsys package dexopt` reports
 * it as run-from-apk — so every launch verifies the whole app's classes at
 * runtime before a line of our code runs. Measured on the BlueStacks box, that
 * was about two seconds of every cold start and most of why the dev build felt
 * so much slower than a release one. `verify` is the cheapest filter that
 * removes it (~15s once per install), and unlike `speed` it leaves the debug
 * build debuggable exactly as before.
 *
 * Runs after `installDevDebug` from the command line. Android Studio's Run
 * button deploys on its own and never reaches this task, so from there run
 * `./gradlew verifyDevInstall` after installing.
 */
val verifyDevInstall = tasks.register("verifyDevInstall") {
    group = "install"
    description = "Pre-verifies the installed dev build on every connected device."
    val adb = androidComponents.sdkComponents.adb
    doLast {
        val adbPath = adb.get().asFile.absolutePath
        val serials = ProcessBuilder(adbPath, "devices").start()
            .inputStream.bufferedReader().readLines()
            .drop(1)
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 && it[1] == "device" }?.get(0) }
        serials.forEach { serial ->
            logger.lifecycle("verifyDevInstall: compiling com.dev.bitchord on $serial")
            ProcessBuilder(
                adbPath, "-s", serial, "shell", "cmd", "package", "compile",
                "-m", "verify", "-f", "com.dev.bitchord",
            ).inheritIO().start().waitFor()
        }
    }
}
tasks.matching { it.name == "installDevDebug" }.configureEach { finalizedBy(verifyDevInstall) }

/**
 * Third-party licences that have to travel inside the APK, not just in the
 * repository: the loop-mix renderer compiles Signalsmith Stretch and
 * Signalsmith Linear into the app's native library, and their MIT licences
 * ask for the copyright notice and permission text to go wherever the code
 * goes. Packaged as `assets/licenses/<name>.txt`, copied from the vendored
 * trees so there is one copy to keep current. The draft-APK workflow fails a
 * build whose APK lacks them.
 */
abstract class ThirdPartyNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licences: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("licenses")
        dir.deleteRecursively()
        dir.mkdirs()
        // Named after the vendored directory each licence sits in.
        licences.files.forEach { file -> file.copyTo(dir.resolve("${file.parentFile.name}.txt")) }
    }
}

val thirdPartyNotices = tasks.register<ThirdPartyNotices>("thirdPartyNotices") {
    val vendored = rootProject.layout.projectDirectory.dir("native/third_party")
    licences.from(
        vendored.file("signalsmith-stretch/LICENSE.txt"),
        vendored.file("signalsmith-linear/LICENSE.txt"),
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(thirdPartyNotices, ThirdPartyNotices::outputDir)
    }
}
