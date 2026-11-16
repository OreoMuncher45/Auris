/*
 * The two compiled-class checks, wired to every compile a module names.
 *
 * - checkConstructorWidths*: no constructor wider than dex can call (254
 *   arguments) — see tools/check_constructor_widths.py.
 * - checkMethodSizes*: no method over 32,000 bytes of bytecode, half the
 *   JVM's limit — see tools/check_method_sizes.py.
 *
 * A module sets, before applying this file,
 *
 *     extra["classChecks"] = mapOf("compileDevReleaseKotlin" to "tmp/kotlin-classes/devRelease", ...)
 *
 * mapping each Kotlin compile task to the class directory it writes, relative
 * to the module's build directory. Each gets a check task of each kind that
 * runs after it (finalizedBy) and depends on it, so a check run on its own
 * still has classes to read.
 *
 * Every named compile task must exist: the wiring uses tasks.named(), which
 * fails the build on a name that matches nothing. A compile task renamed by
 * an Android Gradle Plugin or Kotlin upgrade therefore breaks the build here,
 * instead of quietly leaving its classes unchecked.
 */

@Suppress("UNCHECKED_CAST")
val classChecks = extra["classChecks"] as Map<String, String>

check(classChecks.isNotEmpty()) { "${project.path}: classChecks names no compile task to check" }

val checkScripts = mapOf(
    "checkConstructorWidths" to "tools/check_constructor_widths.py",
    "checkMethodSizes" to "tools/check_method_sizes.py",
)

val wiring = classChecks.map { (compileTask, classesDir) ->
    val suffix = compileTask.removePrefix("compile")
    val checks = checkScripts.map { (prefix, script) ->
        tasks.register<Exec>(prefix + suffix) {
            group = "verification"
            description = "Runs $script on the classes $compileTask writes."
            dependsOn(compileTask)
            commandLine(
                "python3",
                "-B",
                rootProject.file(script).absolutePath,
                layout.buildDirectory.dir(classesDir).get().asFile.absolutePath,
            )
        }
    }
    compileTask to checks
}

afterEvaluate {
    wiring.forEach { (compileTask, checks) ->
        tasks.named(compileTask) { finalizedBy(checks) }
    }
}
