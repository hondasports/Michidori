package com.michidori.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.michidori.app.recording.RecordingService
import com.michidori.app.recording.RecordingStatus
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingServiceInstrumentedTest {
    @Test
    fun serviceRecordsAndStopsWithoutDepthCrashingTheProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, RecordingService::class.java)
        var service: RecordingService? = null
        val connected = java.util.concurrent.CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? RecordingService.LocalBinder)?.service()
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        ContextCompat.startForegroundService(context, intent)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val boundService = service
            assertNotNull(boundService)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                boundService?.startRecording()
            }
            assertTrue(awaitStatus(boundService, RecordingStatus.RECORDING))
            TimeUnit.SECONDS.sleep(3)
            assertEquals(RecordingStatus.RECORDING, boundService?.uiState?.value?.status)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                boundService?.stopRecording()
            }
            assertTrue(awaitStatus(boundService, RecordingStatus.IDLE))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                service?.stopRecording()
            }
            context.unbindService(connection)
            context.stopService(intent)
        }
    }

    private fun awaitStatus(service: RecordingService?, expected: RecordingStatus): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (service?.uiState?.value?.status == expected) return true
            TimeUnit.MILLISECONDS.sleep(100)
        }
        return false
    }
}
