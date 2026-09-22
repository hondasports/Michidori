package com.michidori.app.vision

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
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

    @Test
    fun compilesAndRunsDetector() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Environment.create(BuiltinNpuAcceleratorProvider(context)).use { environment ->
            val available = environment.getAvailableAccelerators()
            var model: CompiledModel? = null
            var used: Accelerator? = null
            for (accelerator in listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)) {
                if (accelerator != Accelerator.CPU && accelerator !in available) continue
                runCatching {
                    CompiledModel.create(
                        context.assets,
                        DETECTOR_ASSET,
                        CompiledModel.Options(accelerator),
                    )
                }.onSuccess {
                    model = it
                    used = accelerator
                }.onFailure { Log.w(TAG, "compile on $accelerator failed", it) }
                if (model != null) break
            }
            val activeModel = model ?: error("no accelerator compiled the model")
            Log.i(TAG, "detector compiled on $used")
            val inputs = activeModel.createInputBuffers()
            val outputs = activeModel.createOutputBuffers()
            Log.i(TAG, "input buffers=${inputs.size} output buffers=${outputs.size}")
            runCatching {
                inputs.forEach { buffer ->
                    runCatching { buffer.writeInt8(ByteArray(320 * 320 * 3)) }
                        .onFailure { buffer.writeFloat(FloatArray(320 * 320 * 3)) }
                }
                activeModel.run(inputs, outputs)
            }.onFailure { Log.w(TAG, "run failed", it) }
            runCatching {
                val scores = outputs[0].readFloat()
                val top = scores.sortedDescending().take(10)
                Log.i(
                    TAG,
                    "score stats min=${scores.minOrNull()} max=${scores.maxOrNull()} top10=$top",
                )
            }
            outputs.forEachIndexed { index, buffer ->
                runCatching { Log.i(TAG, "out[$index] float32 elements=${buffer.readFloat().size}") }
                    .onFailure { Log.i(TAG, "out[$index] float32 read failed: ${it.message}") }
            }
            inputs.forEach { runCatching { it.close() } }
            outputs.forEach { runCatching { it.close() } }
            activeModel.close()
            assertTrue(true)
        }
    }

    private companion object {
        const val TAG = "LiteRtProbe"
        const val DETECTOR_ASSET = "efficientdet_lite0.tflite"
    }
}
