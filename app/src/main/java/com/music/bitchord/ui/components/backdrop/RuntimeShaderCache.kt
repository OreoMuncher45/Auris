/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.music.bitchord.ui.components.backdrop

import org.intellij.lang.annotations.Language

sealed interface RuntimeShaderCache {

    fun obtainRuntimeShader(key: String, @Language("AGSL") string: String): RuntimeShader
}

/**
 * Vendored change: every cache reads one process-wide set of compiled shaders.
 *
 * Upstream gives each glass surface, and each highlight, a private map, so a
 * screen with eight glass surfaces compiled the refraction shader eight times
 * (SkSL parse, optimise and codegen on the UI thread, on that surface's first
 * draw) and held eight copies of it. Clearing on detach meant a surface that
 * came back — a menu reopened, a page pushed again — compiled it once more.
 *
 * Sharing one instance per shader is safe because every user sets its uniforms
 * and consumes them in the same main-thread call: a RenderEffect built from the
 * shader snapshots its uniforms, and a highlight paint is recorded straight
 * after it is configured. The set is bounded by the number of distinct shader
 * sources in the engine (four), so it never grows with the number of surfaces.
 */
internal object SharedRuntimeShaders {

    private val shaders = java.util.concurrent.ConcurrentHashMap<String, RuntimeShader>()

    /** The shader for [string], compiled on first request from any surface. */
    fun obtain(string: String): RuntimeShader =
        shaders[string] ?: RuntimeShader(string).let { shaders.putIfAbsent(string, it) ?: it }

    /**
     * Compiles [string] off the main thread ahead of first use; see
     * [GlassShaderWarmup]. [configure] runs on the fresh instance before it is
     * published, so no other thread ever sees it being touched.
     */
    fun precompile(string: String, configure: RuntimeShader.() -> Unit) {
        if (shaders.containsKey(string)) return
        val shader = RuntimeShader(string)
        shader.configure()
        shaders.putIfAbsent(string, shader)
    }
}

internal class RuntimeShaderCacheImpl : RuntimeShaderCache {

    // Keyed by source rather than [key]: two call sites can never collide on a name.
    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader =
        SharedRuntimeShaders.obtain(string)

    /** Nothing to release per surface: the compiled shaders are shared and bounded. */
    fun clear() = Unit
}
