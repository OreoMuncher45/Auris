package com.music.bitchord.ui.player

import android.app.AlertDialog
import android.content.Context
import android.media.MediaRouter
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.os.Build
import android.widget.ArrayAdapter
import com.music.bitchord.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Let Android route the media session, including connected Bluetooth outputs. */
@Suppress("DEPRECATION")
internal fun openAudioOutput(context: Context) {
    if (Build.VERSION.SDK_INT >= 34 &&
        runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
    ) return

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
    val modernRouter = remember(context) {
        if (Build.VERSION.SDK_INT >= 30) MediaRouter2.getInstance(context) else null
    }
    fun selectedOutput(): Pair<String, Boolean> {
        if (Build.VERSION.SDK_INT >= 30 && modernRouter != null) {
            val routes = modernRouter.systemController.selectedRoutes
            if (routes.isNotEmpty()) return routes.joinToString { it.name.toString() } to
                routes.all { it.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER }
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
            val callback = object : MediaRouter2.ControllerCallback() {
                override fun onControllerUpdated(controller: MediaRouter2.RoutingController) {
                    output = selectedOutput()
                }
            }
            modernRouter.registerControllerCallback(context.mainExecutor, callback)
            output = selectedOutput()
            onDispose { modernRouter.unregisterControllerCallback(callback) }
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
