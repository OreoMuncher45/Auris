package com.music.bitchord.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** Lazy lists crash on a repeated key, so [uniqueKeys] must never produce one. */
class LazyKeysTest {

    private fun keysOf(vararg bases: String) = uniqueKeys(bases.toList()) { it }

    private fun assertDistinct(keys: List<String>) =
        assertEquals(keys.size, keys.toSet().size, "duplicate keys in $keys")

    @Test
    fun `a repeated base after one that already ends in a count stays unique`() {
        assertDistinct(keysOf("Top", "Top", "Top#1"))
    }

    @Test
    fun `a base that ends in a count before its repeat stays unique`() {
        assertDistinct(keysOf("Top#1", "Top", "Top"))
    }

    @Test
    fun `the same list gives the same keys`() {
        assertEquals(keysOf("a", "b", "a"), keysOf("a", "b", "a"))
    }
}
