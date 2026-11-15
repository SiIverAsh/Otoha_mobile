package io.github.otoha.mobile

import android.os.Looper
import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaSessionService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupTest {
    @Test
    fun playbackServiceCanInitializeOnAndroid16() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun mainScreenAndBoundPlayerSurviveColdStartOnAndroid16() {
        val application = RuntimeEnvironment.getApplication() as OtohaApplication
        // Never contact a real source in a local startup regression test.
        application.store.setAsmrOneApiBaseUrl("https://127.0.0.1:1")
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val binder = service.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
        shadowOf(application).setComponentNameAndServiceForBindService(
            ComponentName(application, PlaybackService::class.java), binder)
        val activity = Robolectric.buildActivity(MainActivity::class.java)
        try {
            activity.setup().visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        } finally {
            activity.pause().stop().destroy()
            service.destroy()
        }
    }
}
