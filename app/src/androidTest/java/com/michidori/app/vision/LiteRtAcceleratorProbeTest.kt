package com.michidori.app.vision

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.Environment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiteRtAcceleratorProbeTest {
    @Test
    fun reportsAvailableAccelerators() {
        Environment.create().use { environment ->
            val accelerators = environment.getAvailableAccelerators()
            Log.i(TAG, "LiteRT available accelerators: $accelerators")
            assertTrue(accelerators.contains(Accelerator.CPU))
        }
    }

    private companion object {
        const val TAG = "LiteRtProbe"
    }
}
