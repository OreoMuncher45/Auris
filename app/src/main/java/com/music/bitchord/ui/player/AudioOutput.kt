package com.music.bitchord.ui.player

import android.app.AlertDialog
import android.content.Context
import android.media.MediaRouter
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.os.Build
import android.widget.ArrayAdapter
import androidx.annotation.RequiresApi
import com.music.bitchord.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Every [MediaRouter2] reference in this file, held in a class of its own.
 *
 * An inline `SDK_INT >= 30` guard is not enough. ART resolves the classes a
 * method names when it verifies that method — which is the first time the
 * method runs, not the first time the guarded branch is taken — so a method
 * that merely *mentions* MediaRouter2 throws NoClassDefFoundError on Android 9
 * whether or not the guard lets it through. Opening the player crashed outright
 * on API 28 for exactly this reason.
 *
 * Behind its own class the reference is named only by methods on that class,
 * and the class is never loaded on a device that does not have the API.
 */
@RequiresApi(30)
private object ModernRoutes {
    fun instance(context: Context): Any = MediaRouter2.getInstance(context)

    /** The selected route names, and whether all of them are the built-in speaker. */
    fun selected(router: Any): Pair<String, Boolean>? {
        val routes = (router as MediaRouter2).systemController.selectedRoutes
        if (routes.isEmpty()) return null
        return routes.joinToString { it.name.toString() } to
            routes.all { it.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER }
    }

    /** Registers [onChange] and hands back the matching unregister. */
    fun observe(router: Any, context: Context, onChange: () -> Unit): () -> Unit {
        val modern = router as MediaRouter2
        val callback = object : MediaRouter2.ControllerCallback() {
            override fun onControllerUpdated(controller: MediaRouter2.RoutingController) = onChange()
        }
        modern.registerControllerCallback(context.mainExecutor, callback)
        return { modern.unregisterControllerCallback(callback) }
    }

    @RequiresApi(34)
    fun showSystemSwitcher(context: Context): Boolean =
        runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
}

/** Let Android route the media session, including connected Bluetooth outputs. */
@Suppress("DEPRECATION")
internal fun openAudioOutput(context: Context) {
    if (Build.VERSION.SDK_INT >= 34 && ModernRoutes.showSystemSwitcher(context)) return

    // Older Android versions expose audio routes through the framework router.
    // Keep the chooser live as devices connect/disconnect while it is open.
    val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    val routes = mutableListOf<MediaRouter.RouteInfo>()
    val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_single_choice)
    val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.audio_output)
        .setSingleChoiceItems(adapter, -1) { dialog, index ->
            routes.getOrNull(index)?.takeIf { it.isEnabled }?.let {
                router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, it)
            }
            dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    fun refresh() {
        routes.clear()
        routes.addAll((0 until router.routeCount).map(router::getRouteAt).filter {
            it.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_AUDIO != 0 && it.isEnabled
        })
        adapter.clear()
        adapter.addAll(routes.map { it.name.toString() })
        val selected = routes.indexOf(router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO))
        dialog.listView?.setItemChecked(selected, true)
    }
    val callback = object : MediaRouter.SimpleCallback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteSelected(router: MediaRouter, type: Int, route: MediaRouter.RouteInfo) = refresh()
    }
    dialog.setOnDismissListener { router.removeCallback(callback) }
    router.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)
    dialog.show()
    refresh()
}

/** Observe the selected route, rather than guessing from the list of connected devices. */
@Suppress("DEPRECATION")
@Composable
internal fun rememberAudioOutputName(accountName: String?): String {
    val context = LocalContext.current
    val router = remember(context) {
        context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    }
    // Held as Any so this composable's own body never names the class either;
    // see [ModernRoutes].
    val modernRouter: Any? = remember(context) {
        if (Build.VERSION.SDK_INT >= 30) ModernRoutes.instance(context) else null
    }
    fun selectedOutput(): Pair<String, Boolean> {
        if (Build.VERSION.SDK_INT >= 30 && modernRouter != null) {
            ModernRoutes.selected(modernRouter)?.let { return it }
        }
        val route = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        return route.name.toString() to
            (route == router.defaultRoute && route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER)
    }
    var output by remember(router) { mutableStateOf(selectedOutput()) }
    DisposableEffect(router) {
        val callback = object : MediaRouter.SimpleCallback() {
            override fun onRouteSelected(router: MediaRouter, type: Int, route: MediaRouter.RouteInfo) {
                output = selectedOutput()
            }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                output = selectedOutput()
            }
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                output = selectedOutput()
            }
        }
        router.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)
        output = selectedOutput()
        onDispose { router.removeCallback(callback) }
    }
    DisposableEffect(modernRouter) {
        if (Build.VERSION.SDK_INT >= 30 && modernRouter != null) {
            val stop = ModernRoutes.observe(modernRouter, context) { output = selectedOutput() }
            output = selectedOutput()
            onDispose { stop() }
        } else {
            onDispose { }
        }
    }
    val firstName = accountName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }
    return if (output.second) {
        if (firstName != null) context.getString(R.string.personal_phone, firstName)
        else context.getString(R.string.this_phone)
    } else output.first
}
