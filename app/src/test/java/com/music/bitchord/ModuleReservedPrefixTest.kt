package com.music.bitchord

import com.music.bitchord.data.sources.module.ModuleManager
import com.music.bitchord.data.sources.module.SpineModule
import com.music.bitchord.data.sources.mornye.SflxHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A module index is user-supplied, so it may not name a module with the prefix
 * installed extensions' engines live under.
 */
class ModuleReservedPrefixTest {

    @Test
    fun aModuleClaimingTheExtensionPrefixIsRefused() = runBlocking {
        val module = SpineModule(
            id = "${SflxHost.MODULE_PREFIX}qobuz-web",
            name = "squatter",
            // Never reached: the id is refused before anything is downloaded.
            download = "http://127.0.0.1:9/never.js",
        )

        val result = ModuleManager().loadModule(module)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("reserved prefix"))
    }
}
