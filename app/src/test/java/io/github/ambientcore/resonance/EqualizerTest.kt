@file:Suppress("UnsafeOptInUsageError")

package io.github.ambientcore.resonance

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class EqualizerTest {
    private val shaped = EqualizerSettings().select("VShaped").copy(enabled = true).parameters()
    private fun buffer(samples: FloatArray): ByteBuffer = ByteBuffer.allocate(samples.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach(::putFloat); flip() }
    private fun process(eq: GraphicEqualizer, samples: FloatArray, parameters: EqualizerParameters = shaped): FloatArray {
        val output = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        eq.process(buffer(samples), output, C.ENCODING_PCM_FLOAT, parameters)
        output.flip()
        return FloatArray(samples.size) { output.float }
    }
    private fun gain(rate: Int, frequency: Double, parameters: EqualizerParameters): Double {
        val input = FloatArray(rate) { (.25 * sin(2 * PI * frequency * it / rate)).toFloat() }
        val output = process(GraphicEqualizer(rate, 1, parameters), input, parameters)
        var source = 0.0
        var result = 0.0
        for (i in rate / 2 until rate) { source += input[i].toDouble() * input[i]; result += output[i].toDouble() * output[i] }
        return 10 * log10(result / source)
    }

    @Test fun defaultIsBypassedAndPresetsAndCustomProfilesAreIndependent() {
        val initial = EqualizerSettings()
        assertFalse(initial.enabled)
        assertEquals("Flat", initial.preset)
        val selected = initial.select("VShaped")
        assertFalse(selected.enabled)
        assertTrue(selected.gains.take(3).all { it > 0 })
        assertTrue(selected.gains[5] < 0)
        assertTrue(selected.gains[8] > 0)
        val custom = selected.copy(preset = "Custom", gains = selected.gains.map { it / 2 }).savePreset("Studio")
        assertEquals("Studio", custom.preset)
        assertEquals(selected.gains, EQ_PRESETS.single { it.name == "VShaped" }.gains)
        assertEquals(custom.gains, custom.select("Flat").select("Studio").gains)
        assertEquals("Custom", custom.deletePreset("Studio").preset)
        assertTrue(custom.deletePreset("Studio").customPresets.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { custom.savePreset("Flat") }
        assertThrows(IllegalArgumentException::class.java) { custom.copy(preamp = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { EqualizerSettings(preset = "Custom", gains = List(10) { 13f }) }
    }

    @Test fun measuredResponseMatchesCurveAndVShapeAtEverySampleRate() {
        for (rate in listOf(44_100, 48_000, 96_000, 192_000)) {
            val curve = EqualizerCurve(shaped, rate)
            for (frequency in listOf(31.25, 62.5, 1000.0, 8000.0, 16_000.0)) {
                assertEquals("$rate / $frequency", curve.response(frequency) + curve.effectivePreamp,
                    gain(rate, frequency, shaped), .12)
            }
            assertTrue(gain(rate, 62.5, shaped) - gain(rate, 1000.0, shaped) > 5)
            assertTrue(gain(rate, 16_000.0, shaped) - gain(rate, 1000.0, shaped) > 3)
        }
    }

    @Test fun bypassPreservesEveryPcmPrecisionAndEndiannessEvenWithBoostedSettings() {
        val samples = floatArrayOf(-.75f, .125f, 0f, .75f, -.125f, .5f)
        val bypass = shaped.copy(enabled = false)
        val big = setOf(C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN)
        for (encoding in listOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN)) {
            val input = ByteBuffer.allocate(samples.size * GraphicEqualizer.bytesPerSample(encoding))
                .order(if (encoding in big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            samples.forEach { value ->
                when (encoding) {
                    C.ENCODING_PCM_8BIT -> input.put((value * 128 + 128).toInt().toByte())
                    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> input.putShort((value * 32768).toInt().toShort())
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                        val sample = (value * 8388608).toInt()
                        val shifts = if (encoding in big) listOf(16, 8, 0) else listOf(0, 8, 16)
                        shifts.forEach { input.put((sample shr it).toByte()) }
                    }
                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> input.putInt((value.toDouble() * 2147483648.0).toInt())
                    C.ENCODING_PCM_FLOAT -> input.putFloat(value)
                }
            }
            input.flip()
            val output = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            GraphicEqualizer(96_000, 2, bypass).process(input, output, encoding, bypass)
            output.flip()
            assertArrayEquals(samples, FloatArray(samples.size) { output.float }, 0f)
        }
        val flat = EqualizerSettings(enabled = true).parameters()
        assertEquals(0.0, EqualizerCurve(flat, 48_000).effectivePreamp, 0.0)
        assertArrayEquals(samples, process(GraphicEqualizer(48_000, 2, flat), samples, flat), 0f)
    }

    @Test fun channelsChunkingAndResetDoNotLeakState() {
        val input = FloatArray(96_000) { if (it % 2 == 0) (.2 * sin(2 * PI * 55 * (it / 2) / 48_000)).toFloat() else 0f }
        val full = process(GraphicEqualizer(48_000, 2, shaped), input)
        val eq = GraphicEqualizer(48_000, 2, shaped)
        assertArrayEquals(full, process(eq, input.copyOfRange(0, 12_000)) + process(eq, input.copyOfRange(12_000, input.size)), 0f)
        assertTrue(full.indices.filter { it % 2 != 0 }.all { full[it] == 0f })
        eq.reset(shaped)
        assertArrayEquals(FloatArray(200), process(eq, FloatArray(200)), 0f)
    }

    @Test fun adaptiveProtectionAvoidsClippingWithoutAConstantPreampCut() {
        val boosted = shaped.copy(gains = List(10) { 12f })
        for (rate in listOf(44_100, 96_000, 192_000)) {
            val curve = EqualizerCurve(boosted, rate)
            assertEquals(0.0, curve.effectivePreamp, 0.0)
            val random = Random(123)
            for (input in listOf(FloatArray(rate) { random.nextFloat() * 2 - 1 },
                FloatArray(rate) { if (sin(2 * PI * 30 * it / rate) >= 0) .999f else -.999f })) {
                val eq = GraphicEqualizer(rate, 1, boosted)
                val result = process(eq, input, boosted)
                assertEquals(0L, eq.clippedSamples)
                assertTrue(result.all { it.isFinite() && abs(it) < 1 })
                assertTrue(result.all { abs(it) <= EQ_PEAK_CEILING + .000001 })
            }
        }
        val manual = EqualizerSettings(enabled = true, preset = "Custom", preamp = 6f, peakProtection = false).parameters()
        val eq = GraphicEqualizer(48_000, 1, manual)
        process(eq, FloatArray(100) { .9f }, manual)
        assertTrue(eq.clippedSamples > 0)
    }

    @Test fun normalMusicRetainsLoudnessWhileBriefPeaksAreProtected() {
        val rate = 48_000
        val input = FloatArray(rate) { frame ->
            (.09 * sin(2 * PI * 50 * frame / rate) + .09 * sin(2 * PI * 1000 * frame / rate) +
                .04 * sin(2 * PI * 8000 * frame / rate)).toFloat()
        }
        input[rate / 2] = .99f
        val protected = process(GraphicEqualizer(rate, 1, shaped), input)
        val rawSettings = shaped.copy(peakProtection = false)
        val reference = process(GraphicEqualizer(rate, 1, rawSettings), input, rawSettings)
        fun energy(values: FloatArray): Double = values.sumOf { it.toDouble() * it }
        val changeDb = 10 * log10(energy(protected) / energy(reference))
        assertTrue("Peak protection should not impose a large overall cut: $changeDb dB", changeDb > -2.0)
        val curve = EqualizerCurve(shaped, rate)
        val oldCut = EQ_FREQUENCIES.maxOf { curve.response(it) } + 6
        assertTrue("Must recover at least 6dB versus the old fixed attenuation", changeDb + oldCut > 6)
        assertTrue(protected.all { abs(it) <= EQ_PEAK_CEILING + .000001 })
    }

    @Test fun protectionLinksStereoAndHandlesTinyBuffersWithoutDelayOrLostTail() {
        val params = EqualizerSettings(enabled = true, preset = "Custom", preamp = 6f).parameters()
        val eq = GraphicEqualizer(48_000, 2, params)
        val output = process(eq, FloatArray(40) { if (it % 2 == 0) .12f else .9f }, params)
        for (frame in 0 until output.size / 2) {
            assertEquals(.12f / .9f, output[frame * 2] / output[frame * 2 + 1], .00001f)
            assertTrue(abs(output[frame * 2 + 1]) <= EQ_PEAK_CEILING + .000001)
        }
        val mono = GraphicEqualizer(192_000, 1, params)
        for (length in listOf(1, 3, 127, 4096, 2)) {
            val input = FloatArray(length).apply { this[0] = .99f }
            val result = process(mono, input, params)
            assertEquals(length, result.size)
            assertTrue("First sample must not be delayed", result[0] > 0f)
            assertTrue(result.drop(1).all { it == 0f })
            assertTrue(result.all { abs(it) <= EQ_PEAK_CEILING + .000001 })
        }
        val flat = EqualizerSettings(enabled = true).parameters()
        val samples = floatArrayOf(.999f, -.999f, .25f, 0f)
        assertArrayEquals(samples, process(GraphicEqualizer(48_000, 1, flat), samples, flat), 0f)
    }

    @Test fun preampAndPresetChangesFadeWithoutRestartingAndRejectInvalidSamples() {
        val flat = EqualizerSettings(enabled = true).parameters()
        val quiet = flat.copy(preamp = -6f)
        val eq = GraphicEqualizer(48_000, 1, flat)
        val result = process(eq, FloatArray(4800) { .5f }, quiet)
        assertTrue((1 until result.size).all { abs(result[it] - result[it - 1]) < .002 })
        assertEquals(.25f, result.last(), .001f)
        assertThrows(IllegalArgumentException::class.java) { process(eq, floatArrayOf(Float.NaN), quiet) }
        assertThrows(IllegalArgumentException::class.java) {
            eq.process(ByteBuffer.allocate(1), ByteBuffer.allocate(4), C.ENCODING_PCM_16BIT, quiet)
        }
    }

    @Test fun sinkPreservesRateAndDoesNotRefilterUnderBackpressure() {
        var configured: Format? = null
        val written = mutableListOf<Float>()
        var calls = 0
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "configure" -> { configured = args[0] as Format; null }
                "handleBuffer" -> {
                    val input = args[0] as ByteBuffer
                    repeat(if (calls++ == 0) 2 else input.remaining() / 4) { written += input.float }
                    !input.hasRemaining()
                }
                "flush", "reset" -> null
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                else -> error("Unexpected fake sink method: ${method.name}")
            }
        } as AudioSink
        val sink = EqualizerAudioSink(delegate) { shaped }
        sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(96_000)
            .setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_FLOAT).setEncoderDelay(7).setEncoderPadding(4).build(), 0, null)
        assertEquals(96_000, configured?.sampleRate)
        assertEquals(C.ENCODING_PCM_FLOAT, configured?.pcmEncoding)
        assertEquals(7, configured?.encoderDelay)
        assertEquals(4, configured?.encoderPadding)
        val samples = floatArrayOf(.2f, -.1f, .3f, .1f, -.2f, .2f)
        val input = buffer(samples)
        assertFalse(sink.handleBuffer(input, 0, 1))
        assertEquals(0, input.position())
        assertTrue(sink.handleBuffer(input, 0, 1))
        assertEquals(input.limit(), input.position())
        assertArrayEquals(process(GraphicEqualizer(96_000, 2, shaped), samples), written.toFloatArray(), 0f)
        sink.flush()
        assertTrue(sink.handleBuffer(buffer(FloatArray(4)), 1, 1))
        assertTrue(written.takeLast(4).all { it == 0f })
    }
}
