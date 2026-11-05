import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The Compose UI both applications draw from — the player first.
 *
 * Library versions are pinned to the Compose Multiplatform release whose
 * Android artifacts are the androidx ones the phone app already runs (CMP
 * 1.10.x -> androidx 1.10.x, material3 1.7.3 -> androidx material3 1.3.1), so
 * depending on this module never moves the phone's Compose underneath it. The
 * desktop resolves its own newer versions over these at the top of its graph.
 *
 * The pin is the *Android* anchor only. On the JVM it is a floor, not a
 * ceiling — and there it matters which version compiles this module, because
 * the desktop resolves ui-desktop 1.12.0 / skiko 0.150.x at runtime. Skia's
 * Matrix33 became an inline float[9] between skiko 0.9 and 0.15
 * (`makeTranslate` went from returning a Matrix33 to a name-mangled float[]
 * overload), so bytecode compiled against 0.9 linked its object call and
 * crashed with NoSuchMethodError on the desktop the first time a lyric line
 * grew. Compiling the module against the version the desktop actually
 * resolves keeps the compile-time link and the runtime link the same.
 */
val cmp = "1.10.3"

/** What the desktop graph resolves; see the comment above. */
val CMP_DESKTOP =
    // Haze 1.7.x, which the Windows build uses (see desktopApp/build.gradle.kts), was compiled
    // against a ShaderBrush.createShader that returns skia's Shader; 1.12 changed that return
    // type, so on 1.12 every blur dies with NoSuchMethodError. Windows stays on the plugin's
    // own 1.10.3 / skiko 0.9; only Linux moves to 1.12.
    if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "1.10.3" else "1.12.0"

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Both targets are JVMs, so the UI is written once against the JDK here
        // rather than in commonMain.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            api(project(":shared"))
            api("org.jetbrains.compose.runtime:runtime:$cmp")
            api("org.jetbrains.compose.foundation:foundation:$cmp")
            api("org.jetbrains.compose.animation:animation:$cmp")
            api("org.jetbrains.compose.ui:ui:$cmp")
            api("org.jetbrains.compose.material3:material3:1.7.3")
            api("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            api("org.jetbrains.compose.components:components-resources:$cmp")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
            api("io.coil-kt.coil3:coil-compose:3.0.4")
            api("dev.chrisbanes.haze:haze:1.3.1")
            api("dev.chrisbanes.haze:haze-materials:1.3.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        jvmSharedMain.dependencies {
            // Spotify's canvas requests are protobuf, and this source set is
            // compiled for both targets - so the library has to be on both
            // classpaths, not only the desktop's.
            implementation("com.google.protobuf:protobuf-javalite:4.35.0")
        }
        // Compiled against what the desktop actually resolves, not the phone's
        // pin — see the CMP_DESKTOP comment at the top of this file. Skia's
        // Matrix33 changed shape between skiko 0.9 and 0.15, and bytecode built
        // against 0.9 NoSuchMethodError'd the desktop's 0.15 at draw time.
        jvmMain.dependencies {
            api("org.jetbrains.compose.ui:ui-desktop:$CMP_DESKTOP")
            // The JSON the phone reads with the platform's own org.json, which is not on a JVM.
            implementation("org.json:json:20240303")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
        }
        androidMain.dependencies {
            // The phone's own versions, so nothing here moves them.
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.core:core-ktx:1.15.0")
            implementation("androidx.appcompat:appcompat:1.7.0")
            implementation("androidx.palette:palette-ktx:1.0.0")
        }
    }
}

android {
    namespace = "com.music.bitchord.sharedui"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.music.bitchord.sharedui.resources"
    generateResClass = always
}

/**
 * The phone's wording, folded into this module's catalogues.
 *
 * A screen that moves out of the phone's module and into this one reads its
 * strings from here, so the translation written in `app/src/main/res` has to be
 * here too — in every language the phone has it in, or the screen reads as
 * English the moment it leaves the phone.
 *
 * Keys this catalogue already answers are left as they are: it is the one the
 * shared screens read, and where it and the phone disagree, this side is the
 * one that was written for the shared surface. The phone keeps its own copy,
 * so nothing on the phone changes.
 */
val syncPhoneStrings by tasks.registering {
    val phoneRes = rootProject.file("app/src/main/res")
    val sharedRes = project.file("src/commonMain/composeResources")
    inputs.dir(phoneRes)
    // The catalogues it writes, not the whole resource tree: the rest of the tree is read by
    // other tasks, and claiming it here would make them look like they depend on this one.
    outputs.files(
        sharedRes.listFiles { file: File -> file.isDirectory && file.name.startsWith("values") }
            ?.map { File(it, "strings.xml") }
            .orEmpty(),
    )
    doLast {
        val stringEntry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val pluralEntry = Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)

        fun catalogue(text: String): Map<String, String> =
            stringEntry.findAll(text).associate { match ->
                match.groupValues[1] to match.groupValues[2]
            }

        val phone = phoneRes.listFiles { file: File -> file.isDirectory && file.name.startsWith("values") }
            ?.mapNotNull { folder ->
                val strings = File(folder, "strings.xml")
                if (strings.isFile) folder.name to strings.readText() else null
            }?.toMap()
            .orEmpty()

        sharedRes.listFiles { file: File -> file.isDirectory && file.name.startsWith("values") }
            ?.forEach { folder ->
                val target = File(folder, "strings.xml")
                if (!target.isFile) return@forEach
                val phoneText = phone[folder.name] ?: phone["values"] ?: return@forEach
                val existing = catalogue(target.readText())
                val missing = catalogue(phoneText).filterKeys { it !in existing }
                val additions = missing.entries.map { (name, value) ->
                    // Android escapes an apostrophe for its own parser; Compose reads the text as
                    // it stands, so the escape would reach the screen as a backslash.
                    val clean = value.replace("\\'", "'").replace("\\\"", "\"")
                    "    <string name=\"$name\">$clean</string>"
                }
                // Plurals too: a count reads differently from one, and Compose Resources carries
                // them the same way it carries strings.
                val existingPlurals = pluralEntry.findAll(target.readText()).map { it.groupValues[1] }.toSet()
                val pluralAdditions = pluralEntry.findAll(phoneText)
                    .filter { it.groupValues[1] !in existingPlurals }
                    .map { plural ->
                        "    <plurals name=\"${plural.groupValues[1]}\">${plural.groupValues[2].trim()}</plurals>"
                    }
                val all = additions + pluralAdditions
                if (all.isEmpty()) return@forEach
                val text = target.readText()
                target.writeText(text.replace("</resources>", all.joinToString("\n") + "\n</resources>"))
                logger.lifecycle("Auris: ${all.size} phone strings added to ${folder.name}")
            }
    }
}

// The catalogues are read when the resource class is generated, so the merge has to have happened
// before anything that reads the resource tree — and the merged files are an input to those reads,
// so a catalogue that changed is noticed even when the task graph was already warm.
tasks.matching {
    it.name.contains("ValueResourcesFor", ignoreCase = true) ||
        it.name.contains("prepareComposeResourcesTask", ignoreCase = true) ||
        it.name.contains("generateComposeResClass")
}.configureEach {
    dependsOn(syncPhoneStrings)
    inputs.files(syncPhoneStrings)
}
