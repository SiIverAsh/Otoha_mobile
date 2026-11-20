package io.github.otoha.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackNetworkMonitorTest {
    @Test fun waitsForValidationAndIgnoresCallbacksFromThePreviousNetwork() {
        val app = RuntimeEnvironment.getApplication()
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val manager = shadowOf(connectivity)
        manager.setActiveNetworkInfo(null)
        val existing = manager.networkCallbacks.toSet()
        val updates = mutableListOf<Boolean>()
        val monitor = PlaybackNetworkMonitor(app) { updates.add(it) }
        monitor.start()
        val listener = (manager.networkCallbacks - existing).single()
        val wifi = ShadowNetwork.newInstance(41)
        val mobile = ShadowNetwork.newInstance(42)
        val pending = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val validated = NetworkCapabilities(pending).also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        try {
            listener.onAvailable(wifi)
            listener.onCapabilitiesChanged(wifi, pending)
            assertEquals(listOf(false), updates)
            listener.onCapabilitiesChanged(wifi, validated)
            listener.onAvailable(mobile)
            listener.onLost(wifi)
            listener.onCapabilitiesChanged(wifi, pending)
            listener.onCapabilitiesChanged(mobile, validated)
            assertEquals(listOf(false, true), updates)
            listener.onLost(mobile)
            assertEquals(listOf(false, true, false), updates)
        } finally {
            monitor.close()
        }
        assertFalse(listener in manager.networkCallbacks)
        listener.onAvailable(wifi)
        listener.onCapabilitiesChanged(wifi, validated)
        assertEquals(listOf(false, true, false), updates)
    }
}
