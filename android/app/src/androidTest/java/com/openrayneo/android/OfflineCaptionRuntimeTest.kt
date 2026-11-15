package com.openrayneo.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native ABI, packaged model and lifecycle smoke test; no Bluetooth, microphone or network. */
@RunWith(AndroidJUnit4::class)
class OfflineCaptionRuntimeTest {
    @Test fun loadsBundledModelAndProcessesSilenceOnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var captions: LocalCaptions
        instrumentation.runOnMainSync {
            captions = LocalCaptions(instrumentation.targetContext) {
                if (captions.state.ready) ready.countDown()
            }
            captions.start()
        }
        try {
            assertTrue("Bundled native model did not load: ${captions.state.detail}", ready.await(45, TimeUnit.SECONDS))
            repeat(10) { captions.consume(ShortArray(4800), shortArrayOf()); Thread.sleep(30) }
            Thread.sleep(500)
            assertTrue(captions.state.active)
            assertEquals("", captions.state.transcript)
            assertEquals("", captions.state.partial)
        } finally { instrumentation.runOnMainSync { captions.close() } }
    }
}
