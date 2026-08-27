package dev.eclipse.ssh.feature.quickconnect

import android.content.Intent

/**
 * The contract between the home-screen widget, the Quick Settings tile
 * and the main activity.
 *
 * Both the widget and the tile route through the main activity with the
 * same intent extra, so the activity has a single code path for
 * "connect to the most-recently-used host": read the extra, look up
 * the host, dial. Centralising the constant means the contract lives
 * in one place; the widget, the tile and the activity all import it.
 */
object QuickConnectContract {
    /**
     * The intent extra that tells the main activity to dial the most
     * recently used host instead of waiting for the user to tap one.
     *
     * The value is a string rather than a typed boolean to keep the
     * `getBooleanExtra` call simple: a missing extra is `false` by
     * `Bundle` convention, which is the right default (the user opened
     * the app normally).
     */
    const val EXTRA_QUICK_CONNECT_LAST: String = "dev.eclipse.ssh.QUICK_CONNECT_LAST"

    /**
     * Reads the intent and returns whether the caller is asking for a
     * quick connect. Centralised so every entry point applies the same
     * rule: any intent whose action is `ACTION_VIEW` with the extra
     * set, or a direct `ACTION_MAIN` with the extra, qualifies.
     */
    fun isQuickConnect(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_QUICK_CONNECT_LAST, false) == true
}
