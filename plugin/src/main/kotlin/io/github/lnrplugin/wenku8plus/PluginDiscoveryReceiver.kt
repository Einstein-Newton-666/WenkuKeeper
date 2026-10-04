package io.github.lnrplugin.wenku8plus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Responds to the host's plugin discovery broadcast.
 *
 * The host only needs the receiver to exist so that it can be declared in the manifest;
 * no work has to happen in [onReceive].
 */
class PluginDiscoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {}
}
