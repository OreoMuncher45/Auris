package com.music.bitchord.ui.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mesh backdrop's ink surface is always the surface of the mesh actually
 * on screen — including when a track change lands while that surface is
 * still being calculated off the main thread.
 *
 * Meshes here are stand-ins: a mesh is just the band colours it averages to,
 * so the publisher can be driven without a bitmap.
 */
class MeshInkPublisherTest {

    private class FakeMesh(val bands: BandBounds)

    private val white = 0xFFFFFFFF.toInt()
    private val bright = FakeMesh(BandBounds.uniform(IntArray(INK_BANDS) { white }))
    private val navy = FakeMesh(BandBounds.uniform(IntArray(INK_BANDS) { 0xFF1B2440.toInt() }))

    private fun InkSurface.sameAs(other: InkSurface) =
        light.contentEquals(other.light) && dark.contentEquals(other.dark)

    @Test
    fun aNullMeshBeforeAnyMeshIsTheFallback() = runBlocking {
        val published = mutableListOf<InkSurface>()
        val publisher = MeshInkPublisher<FakeMesh>(bands = { it.bands }, publish = { published += it })
        publisher.onMesh(null)
        assertTrue(published.single().sameAs(fallbackMeshInkSurface()))
    }

    @Test
    fun eachMeshPublishesItsOwnBands() = runBlocking {
        val published = mutableListOf<InkSurface>()
        val publisher = MeshInkPublisher<FakeMesh>(bands = { it.bands }, publish = { published += it })
        publisher.onMesh(bright)
        publisher.onMesh(navy)
        assertTrue(published[0].sameAs(meshInkSurface(bright.bands)))
        assertTrue(published[1].sameAs(meshInkSurface(navy.bands)))
    }

    @Test
    fun aNullMeshKeepsTheRetainedMeshsBands() = runBlocking {
        // The backdrop keeps drawing the mesh it had while the next is null.
        val published = mutableListOf<InkSurface>()
        val publisher = MeshInkPublisher<FakeMesh>(bands = { it.bands }, publish = { published += it })
        publisher.onMesh(bright)
        publisher.onMesh(null)
        assertTrue(published.last().sameAs(meshInkSurface(bright.bands)))
    }

    @Test
    fun aTrackChangeMidCalculationStillPublishesTheRetainedMesh() = runBlocking(Dispatchers.Unconfined) {
        // The calculation for the bright mesh is held open, then cancelled by
        // a change to a track with no mesh — as LaunchedEffect(mesh) would.
        // The backdrop is showing the bright mesh by then, so the surface
        // published for the null must be the bright mesh's, not the fallback.
        val gate = CompletableDeferred<Unit>()
        val published = mutableListOf<InkSurface>()
        val publisher = MeshInkPublisher<FakeMesh>(
            bands = { mesh ->
                gate.await()
                mesh.bands
            },
            publish = { published += it },
        )
        val first = launch(start = CoroutineStart.UNDISPATCHED) { publisher.onMesh(bright) }
        yield()
        assertTrue(published.isEmpty(), "the calculation is still held open")
        first.cancel()
        gate.complete(Unit)
        publisher.onMesh(null)

        assertEquals(1, published.size)
        assertTrue(
            published.single().sameAs(meshInkSurface(bright.bands)),
            "a cancelled calculation must not leave the fallback published under a retained mesh",
        )
    }
}
