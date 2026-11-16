package com.music.bitchord.ui.components.backdrop

import android.os.Process
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.music.bitchord.ui.components.backdrop.internal.AmbientHighlightShaderString
import com.music.bitchord.ui.components.backdrop.internal.DefaultHighlightShaderString
import com.music.bitchord.ui.components.backdrop.internal.RoundedRectRefractionShaderString
import com.music.bitchord.ui.components.backdrop.internal.RoundedRectRefractionWithDispersionShaderString
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Compiles every AGSL shader the glass engine can reach, once per process, at
 * startup. Ported from SpotiFLAC's `MornyeGlassWarmup` (MIT) — the technique,
 * not the code: their warmup exists because their glass shaders also compiled
 * lazily, on first use, inside the first gesture.
 *
 * The engine builds its [RuntimeShader]s lazily: every
 * [BackdropEffectScopeImpl] and every
 * [com.music.bitchord.ui.components.backdrop.highlight.HighlightNode] owns a
 * [RuntimeShaderCacheImpl] that compiles a shader the first time a
 * surface draws with it. On a low-end device that first time can land inside a
 * gesture — the player sheet's glass composes when it is first opened, and a
 * new surface's first draw then pays the whole SkSL compile on the UI thread
 * before the frame goes out. That is the first-swipe stutter the owner
 * reports; this object moves the compile to process start, where nothing
 * animates and a hiccup is invisible.
 *
 * Where compilation happens is what makes a background-thread port safe.
 * `android.graphics.RuntimeShader`'s constructor calls `nativeCreateBuilder`,
 * which runs Skia's SkSL compiler — parse, optimise, codegen — and stores the
 * result on the instance. No RenderNode, Canvas or RenderThread is involved,
 * so construction is pure CPU work any thread may do. The app-level
 * [RuntimeShader] wrapper adds nothing but that call, each instance is
 * configured here before it is published to the shared set (after which only
 * the UI thread touches it), and uniform setting is metadata on the
 * already-compiled builder — which is why it is safe to exercise here too.
 *
 * The list below is the engine's complete AGSL surface: every glass path in
 * this app — [com.music.bitchord.ui.components.liquidGlass], the held-menu
 * glass, the catalog components — funnels into
 * [com.music.bitchord.ui.components.backdrop.effects.lens] or one of the two
 * highlight styles. `blur` and
 * `colorControls` are not AGSL (a RenderEffect and a colour matrix), so they
 * have nothing to warm. Each source is compiled through the engine's own
 * shared set and constructor path, then has its
 * uniforms set exactly as the draw path sets them. The uniform pass doubles
 * as a startup smoke test: if a re-vendored Shaders.kt and its call sites
 * ever drift apart, it fails here as a logged warning, not as an
 * [IllegalArgumentException] thrown mid-gesture.
 *
 * Scheduling follows SpotiFLAC's discipline, mapped to what Android offers.
 * They wait for first-frame rasterisation so their detached rasterisation
 * never competes with an entrance or scroll animation. The half of their fix
 * that waits — the offscreen draw — has no worthwhile equivalent here: AGSL
 * is fully compiled by the constructor, so no GPU work exists to schedule
 * until a real draw, and a detached scene would render on its own graphics
 * context whose compiled programs the UI's RenderThread context cannot see
 * (Skia caches programs per context). With nothing left to schedule later,
 * the right slot for the constructor work is the earliest moment at which
 * nothing can compete with anything: [com.music.bitchord.BitChordApplication]
 * onCreate. No window exists yet, so there is no first frame to wait for and
 * no animation on screen that a background-priority thread could steal from.
 * The first glass frame is still an Activity launch and a first layout away,
 * and the GPU-side program link at first draw is paid by the nav bar's own
 * first frame — at rest, not mid-gesture.
 *
 * Since the engine's caches became one shared set ([SharedRuntimeShaders]),
 * what is compiled here is what the surfaces draw with: a surface composed
 * later finds its shader already built and pays no SkSL parse on the UI
 * thread at all. Before that, each node owned a private cache, so this warmed
 * only Skia's compiler machinery and every surface compiled its own copy at
 * first draw anyway. The four sources are those the engine can reach; the
 * glass pool's multi-shape shader went with the pool.
 *
 * Failure is non-fatal by contract, as in SpotiFLAC: any error is logged and
 * the engine's lazy compile remains the fallback, per shader — one bad source
 * does not stop the others. Below API 33, where AGSL does not exist, this is
 * a no-op through [isRuntimeShaderSupported]. It is deliberately not gated on
 * the Liquid Glass setting: the compile is a one-shot background cost, and
 * mirroring MainActivity's enablement logic here would couple this object to
 * settings that other changes own.
 */
object GlassShaderWarmup {

    private const val TAG = "GlassShaderWarmup"

    /** Application.onCreate is the only intended call site; this keeps a repeat call a no-op. */
    private val started = AtomicBoolean(false)

    /**
     * Compiles the engine's shaders once, off the main thread. Safe to call
     * from any thread; does nothing below API 33 and nothing after the first
     * call.
     */
    fun warmUp() {
        if (!isRuntimeShaderSupported()) return
        if (!started.compareAndSet(false, true)) return
        thread(name = "glass-shader-warmup") {
            // Background scheduling group: startup's own work wins the cores,
            // and finishing late only means the lazy path compiles as before.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            // Into the engine's shared set (see [SharedRuntimeShaders]), which
            // every surface reads. Each instance is configured before it is
            // published, so the UI thread never sees one being touched.
            for (shader in shaders) {
                try {
                    SharedRuntimeShaders.precompile(shader.source, shader.configure)
                } catch (t: Throwable) {
                    // Non-fatal by contract: the engine's lazy compile on
                    // first use remains the fallback for this shader.
                    Log.w(TAG, "warmup failed for '${shader.key}'; lazy compile stays the fallback", t)
                }
            }
        }
    }

    private class WarmupShader(
        val key: String,
        val source: String,
        val configure: RuntimeShader.() -> Unit,
    )

    private val shaders = listOf(
        WarmupShader(
            key = "Refraction",
            source = RoundedRectRefractionShaderString,
        ) {
            setFloatUniform("halfSize", 64f, 32f)
            setFloatUniform("minHalf", 32f)
            setFloatUniform("offset", 0f, 0f)
            setFloatUniform("cornerRadii", floatArrayOf(16f, 16f, 16f, 16f))
            setFloatUniform("refractionHeight", 8f)
            setFloatUniform("refractionAmount", -12f)
            setFloatUniform("depthEffect", 0f)
            setIntUniform("circleMapMode", 0)
        },
        WarmupShader(
            key = "RefractionWithDispersion",
            source = RoundedRectRefractionWithDispersionShaderString,
        ) {
            setFloatUniform("halfSize", 64f, 32f)
            setFloatUniform("minHalf", 32f)
            setFloatUniform("offset", 0f, 0f)
            setFloatUniform("cornerRadii", floatArrayOf(16f, 16f, 16f, 16f))
            setFloatUniform("refractionHeight", 8f)
            setFloatUniform("refractionAmount", -12f)
            setFloatUniform("depthEffect", 0f)
            setFloatUniform("chromaticAberration", 0.5f)
            setIntUniform("circleMapMode", 0)
        },
        WarmupShader(
            key = "Default",
            source = DefaultHighlightShaderString,
        ) {
            setFloatUniform("halfSize", 64f, 32f)
            setFloatUniform("minHalf", 32f)
            setFloatUniform("cornerRadii", floatArrayOf(16f, 16f, 16f, 16f))
            setColorUniform("color", Color.White)
            setFloatUniform("lightDir", 0.70710677f, 0.70710677f)
            setFloatUniform("falloff", 1f)
        },
        WarmupShader(
            key = "Ambient",
            source = AmbientHighlightShaderString,
        ) {
            setFloatUniform("halfSize", 64f, 32f)
            setFloatUniform("minHalf", 32f)
            setFloatUniform("cornerRadii", floatArrayOf(16f, 16f, 16f, 16f))
            setFloatUniform("lightDir", 0.70710677f, 0.70710677f)
            setFloatUniform("falloff", 1f)
        },
    )
}
