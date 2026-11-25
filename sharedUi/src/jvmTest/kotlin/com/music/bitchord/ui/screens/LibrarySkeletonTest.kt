package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.UiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signed out, the library stays Loading forever, and its skeleton's shimmer
 * kept the Library tab rendering every frame while idle.
 */
class LibrarySkeletonTest {

    @Test
    fun signedOutNeverShowsTheSkeleton() {
        assertFalse(showsLibrarySkeleton(signedIn = false, state = UiState.Loading))
    }

    @Test
    fun signedInLoadingShowsTheSkeleton() {
        assertTrue(showsLibrarySkeleton(signedIn = true, state = UiState.Loading))
    }

    @Test
    fun loadedOrFailedShowsNoSkeleton() {
        assertFalse(showsLibrarySkeleton(signedIn = true, state = UiState.Success(Unit)))
        assertFalse(showsLibrarySkeleton(signedIn = true, state = UiState.Error("x")))
    }
}
