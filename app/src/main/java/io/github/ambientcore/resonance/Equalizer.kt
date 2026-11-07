@file:Suppress("UnsafeOptInUsageError")

package io.github.ambientcore.resonance

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

const val EQ_PEAK_CEILING = 0.8912509381337456

private data class EqCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun response(rate: Int, frequency: Double): Double {
        val w = 2 * PI * frequency / rate
        val nr = b0 + b1 * cos(w) + b2 * cos(2 * w)
        val ni = -b1 * sin(w) - b2 * sin(2 * w)
        val dr = 1 + a1 * cos(w) + a2 * cos(2 * w)
        val di = -a1 * sin(w) - a2 * sin(2 * w)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }
    companion object {
        fun peak(rate: Int, frequency: Double, gain: Float): EqCoefficients {
            val a = 10.0.pow(gain / 40.0)
            val w = 2 * PI * frequency / rate
            val alpha = sin(w) / (2 * 1.4)
            val denominator = 1 + alpha / a
            return EqCoefficients((1 + alpha * a) / denominator, -2 * cos(w) / denominator,
                (1 - alpha * a) / denominator, -2 * cos(w) / denominator, (1 - alpha / a) / denominator)
        }
    }
}

private class EqFilter(coefficients: EqCoefficients, channels: Int) {
    private val b0 = coefficients.b0
    private val b1 = coefficients.b1
    private val b2 = coefficients.b2
    private val a1 = coefficients.a1
    private val a2 = coefficients.a2
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)
    fun process(data: DoubleArray, samples: Int, channels: Int) {
        for (channel in 0 until channels) {
            var first = z1[channel]
            var second = z2[channel]
            var index = channel
            while (index < samples) {
                val value = data[index]
                val output = b0 * value + first
                first = b1 * value - a1 * output + second
                second = b2 * value - a2 * output
                data[index] = output
                index += channels
            }
            z1[channel] = first
            z2[channel] = second
        }
    }
}

private class EqPeakProtection(sampleRate: Int) {
    private val previewFrames = (sampleRate * .003).toInt().coerceAtLeast(1)
    private val attack = 1 - exp(-1.0 / (sampleRate * .0005))
    private val release = 1 - exp(-1.0 / (sampleRate * .120))
    private var gain = 1.0
    private var peaks = DoubleArray(0)
    private var future = DoubleArray(0)
    private var indices = IntArray(0)

    fun process(data: DoubleArray, samples: Int, channels: Int) {
        val frames = samples / channels
        if (peaks.size < frames) {
            peaks = DoubleArray(frames)
            future = DoubleArray(frames)
            indices = IntArray(frames)
        }
        for (frame in 0 until frames) {
            var peak = 0.0
            for (channel in 0 until channels) peak = maxOf(peak, abs(data[frame * channels + channel]))
            peaks[frame] = peak
        }
        var head = 0
        var tail = -1
        // Inspect upcoming samples already in this buffer; no audio frames are delayed or dropped.
        for (frame in frames - 1 downTo 0) {
            while (head <= tail && indices[head] > frame + previewFrames) head++
            while (head <= tail && peaks[indices[tail]] <= peaks[frame]) tail--
            indices[++tail] = frame
            future[frame] = peaks[indices[head]]
        }
        for (frame in 0 until frames) {
            val wanted = if (future[frame] > EQ_PEAK_CEILING) EQ_PEAK_CEILING / future[frame] else 1.0
            gain += (wanted - gain) * if (wanted < gain) attack else release
            val safe = if (peaks[frame] > EQ_PEAK_CEILING) EQ_PEAK_CEILING / peaks[frame] else 1.0
            gain = minOf(gain, safe)
            for (channel in 0 until channels) data[frame * channels + channel] *= gain
        }
    }
}

class EqualizerCurve(val parameters: EqualizerParameters, val sampleRate: Int) {
    init { require(sampleRate > 0) { "EQ needs a positive sample rate" } }
    private val coefficients = EQ_FREQUENCIES.mapIndexedNotNull { index, frequency ->
        if (frequency >= sampleRate * .49 || parameters.gains[index] == 0f) null
        else EqCoefficients.peak(sampleRate, frequency, parameters.gains[index])
    }
    fun response(frequency: Double): Double = coefficients.sumOf { it.response(sampleRate, frequency) }
    val effectivePreamp: Double = parameters.preamp.toDouble()

    inner class Bank(private val channels: Int) {
        private val filters = if (parameters.enabled) coefficients.map { EqFilter(it, channels) }.toTypedArray() else emptyArray()
        private val gain = if (parameters.enabled) 10.0.pow(effectivePreamp / 20) else 1.0
        private val protection = if (parameters.enabled && parameters.peakProtection &&
            (coefficients.isNotEmpty() || parameters.preamp != 0f)) EqPeakProtection(sampleRate) else null
        fun process(data: DoubleArray, samples: Int) {
            for (filter in filters) filter.process(data, samples, channels)
            if (gain != 1.0) for (index in 0 until samples) data[index] *= gain
            protection?.process(data, samples, channels)
        }
    }
}

class GraphicEqualizer(val sampleRate: Int, val channels: Int, parameters: EqualizerParameters) {
    init { require(sampleRate > 0 && channels > 0) { "EQ needs a valid sample rate and channel count" } }
    private var current = EqualizerCurve(parameters, sampleRate)
    private var bank = current.Bank(channels)
    private var next: EqualizerCurve? = null
    private var nextBank: EqualizerCurve.Bank? = null
    private var fade = 0
    private var sourceSamples = DoubleArray(0)
    private var targetSamples = DoubleArray(0)
    private val fadeFrames = (sampleRate * .05).toInt().coerceAtLeast(1)
    var clippedSamples = 0L
        private set
    fun reset(parameters: EqualizerParameters) {
        current = EqualizerCurve(parameters, sampleRate)
        bank = current.Bank(channels)
        next = null; nextBank = null; fade = 0; clippedSamples = 0
    }
    fun process(input: ByteBuffer, output: ByteBuffer, encoding: Int, parameters: EqualizerParameters) {
        require(input.remaining() % (bytesPerSample(encoding) * channels) == 0) { "PCM buffer is not frame-aligned" }
        if (next == null && parameters != current.parameters) {
            val curve = EqualizerCurve(parameters, sampleRate)
            next = curve
            nextBank = curve.Bank(channels)
            fade = 0
        }
        input.order(if (encoding in BIG_ENDIAN_ENCODINGS) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val samples = input.remaining() / bytesPerSample(encoding)
        if (sourceSamples.size < samples) sourceSamples = DoubleArray(samples)
        for (index in 0 until samples) {
            val value = when (encoding) {
                    C.ENCODING_PCM_8BIT -> ((input.get().toInt() and 255) - 128) / 128.0
                    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> input.short / 32768.0
                    C.ENCODING_PCM_24BIT -> ((input.get().toInt() and 255) or
                        ((input.get().toInt() and 255) shl 8) or (input.get().toInt() shl 16)) / 8388608.0
                    C.ENCODING_PCM_24BIT_BIG_ENDIAN -> ((input.get().toInt() shl 16) or
                        ((input.get().toInt() and 255) shl 8) or (input.get().toInt() and 255)) / 8388608.0
                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> input.int / 2147483648.0
                    C.ENCODING_PCM_FLOAT -> input.float.toDouble()
                    else -> error("Unsupported PCM encoding")
            }
            require(value.isFinite()) { "Non-finite PCM sample" }
            sourceSamples[index] = value
        }
        val target = nextBank
        if (target != null) {
            if (targetSamples.size < samples) targetSamples = DoubleArray(samples)
            sourceSamples.copyInto(targetSamples, endIndex = samples)
            target.process(targetSamples, samples)
        }
        bank.process(sourceSamples, samples)
        for (frame in 0 until samples / channels) {
            val mix = if (target == null) 0.0 else (++fade).toDouble().coerceAtMost(fadeFrames.toDouble()) / fadeFrames
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                val old = sourceSamples[index]
                val result = if (target == null) old else old + mix * (targetSamples[index] - old)
                check(result.isFinite()) { "Non-finite EQ output" }
                if (result < -1 || result > 1) clippedSamples++
                output.putFloat(result.coerceIn(-1.0, 1.0).toFloat())
            }
        }
        if (target != null && fade >= fadeFrames) {
            current = checkNotNull(next)
            bank = target
            next = null; nextBank = null
        }
    }
    companion object {
        private val BIG_ENDIAN_ENCODINGS = setOf(C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN)
        fun supports(encoding: Int): Boolean = encoding in setOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT) || encoding in BIG_ENDIAN_ENCODINGS
        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> error("Unsupported PCM encoding")
        }
    }
}

class EqualizerAudioSink(sink: AudioSink, private val parameters: () -> EqualizerParameters) : ForwardingAudioSink(sink) {
    private var eq: GraphicEqualizer? = null
    private var encoding = C.ENCODING_INVALID
    private var pending: ByteBuffer? = null
    private var converted = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
    private var inputEnd = 0
    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW && GraphicEqualizer.supports(format.pcmEncoding))
            super.getFormatSupport(format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build())
        else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        require(inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW && GraphicEqualizer.supports(inputFormat.pcmEncoding)) { "EQ needs decoded PCM audio" }
        super.configure(inputFormat.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build(), specifiedBufferSize, outputChannels)
        encoding = inputFormat.pcmEncoding
        eq = GraphicEqualizer(inputFormat.sampleRate, inputFormat.channelCount, parameters())
        pending = null
    }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val equalizer = checkNotNull(eq) { "EQ sink is not configured" }
        if (pending == null) {
            inputEnd = buffer.limit()
            val samples = buffer.remaining() / GraphicEqualizer.bytesPerSample(encoding)
            if (converted.capacity() < samples * 4) converted = ByteBuffer.allocateDirect(samples * 4).order(ByteOrder.LITTLE_ENDIAN)
            converted.clear()
            val clipped = equalizer.clippedSamples
            equalizer.process(buffer.duplicate(), converted, encoding, parameters())
            converted.flip()
            if (equalizer.clippedSamples > clipped) android.util.Log.w("ResonanceEQ", "EQ output clipped; enable peak protection or reduce preamp")
            pending = buffer
        } else check(pending === buffer) { "Previous PCM buffer must finish before replacement" }
        // Backpressure must not cause the same buffer to be filtered twice.
        val consumed = super.handleBuffer(converted, presentationTimeUs, encodedAccessUnitCount)
        if (consumed) { buffer.position(inputEnd); pending = null }
        return consumed
    }
    override fun flush() { super.flush(); pending = null; eq?.reset(parameters()) }
    override fun reset() {
        super.reset(); pending = null; eq = null
        converted = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
    }
}
