package com.michidori.app.vision

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.Environment
import kotlinx.coroutines.runBlocking
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

    @Test
    fun probesNpuAcceleratorProvider() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = BuiltinNpuAcceleratorProvider(context)
        Log.i(
            TAG,
            "NPU provider: supported=${provider.isDeviceSupported()} " +
                "libraryReady=${provider.isLibraryReady()} libraryDir=${provider.getLibraryDir()}",
        )
        if (provider.isDeviceSupported() && !provider.isLibraryReady()) {
            runCatching { provider.downloadLibrary() }
                .onSuccess { Log.i(TAG, "NPU library downloaded to ${provider.getLibraryDir()}") }
                .onFailure { Log.w(TAG, "NPU library download failed", it) }
        }
        Environment.create(provider).use { environment ->
            val accelerators = environment.getAvailableAccelerators()
            Log.i(TAG, "accelerators with NPU provider: $accelerators")
            assertTrue(accelerators.isNotEmpty())
        }
    }

    private companion object {
        const val TAG = "LiteRtProbe"
    }
}
