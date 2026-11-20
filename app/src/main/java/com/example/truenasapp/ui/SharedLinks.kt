package com.example.truenasapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.truenasapp.Graph
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pages shared to TrueNAS Mobile from the browser ("Share → Open in TrueNAS Mobile").
 * Your NAS's own pages open in Expert mode, an app's page opens in the app viewer,
 * anything else is refused.
 */
object SharedLinks {
    /** A shared address waiting until you're signed in (and the UI is ready). */
    internal val pending = MutableStateFlow<String?>(null)

    private val urlPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Pulls the address out of a share. Returns true if the intent was a share we handle. */
    fun accept(context: Context, intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        ).firstOrNull { it.isNotBlank() }
        val url = text?.let { urlPattern.find(it)?.value?.trimEnd('.', ',', ')', ']') }
        if (url == null) {
            Toast.makeText(context.applicationContext, "That share doesn't contain a web address", Toast.LENGTH_LONG).show()
        } else {
            pending.value = url
        }
        return true
    }

    /** Opens [url] in the right place. Call once you're signed in. */
    suspend fun route(context: Context, url: String) {
        var allowed = ContainerAccess.isAllowed(url)
        // App addresses (and names, for the title) are learned from the app list;
        // refresh it if we haven't seen this one yet.
        if (!allowed || (!ContainerAccess.isNasUi(url) && !ContainerAccess.knowsApp(url))) {
            runCatching { Graph.api.apps() }
            allowed = ContainerAccess.isAllowed(url)
        }
        when {
            ContainerAccess.isNasUi(url) -> {
                val u = Uri.parse(url)
                val path = buildString {
                    append(u.encodedPath?.ifBlank { null } ?: "/ui/")
                    u.encodedQuery?.let { append('?').append(it) }
                    u.encodedFragment?.let { append('#').append(it) }
                }
                ExpertController.ask(if (path == "/") "/ui/" else path)
            }
            allowed -> AppWebController.open(ContainerAccess.titleFor(url), url)
            else -> Toast.makeText(
                context.applicationContext,
                "TrueNAS Mobile only opens your NAS and its apps",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
