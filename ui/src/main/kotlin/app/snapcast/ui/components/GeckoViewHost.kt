package app.snapcast.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.Ref
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Displays [session] in a [GeckoView]. Switching tabs swaps the session on the same view; the session
 * is released (not closed: the tab still owns it) when the view leaves the composition.
 */
@Composable
fun GeckoViewHost(session: GeckoSession?, modifier: Modifier = Modifier) {
    val geckoView = remember { Ref<GeckoView>() }
    AndroidView(
        factory = { context -> GeckoView(context).also { geckoView.value = it } },
        modifier = modifier.onGloballyPositioned { geckoView.value?.syncScreenOrigin() },
        update = { view ->
            if (view.session !== session) {
                view.releaseSession()
                session?.let(view::setSession)
                view.syncScreenOrigin()
            }
        },
        onRelease = { view -> view.releaseSession() },
    )
}

/**
 * Tells the session where the view is on screen. GeckoView only does this from gatherTransparentRegion, which
 * Compose never passes on to the child of an AndroidView; without it the page reports its accessibility bounds
 * (TalkBack focus, explore by touch) as if the view started at the top of the screen.
 */
private fun GeckoView.syncScreenOrigin() {
    gatherTransparentRegion(null)
}
