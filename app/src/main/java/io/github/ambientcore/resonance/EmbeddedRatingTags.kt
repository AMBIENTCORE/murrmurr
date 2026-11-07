package io.github.ambientcore.resonance

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.roundToInt

object EmbeddedRatingTags {
    val extensions = setOf("MP3", "FLAC", "OGG", "OPUS", "M4A", "MP4", "WAV")
    private const val MAX_TAG = 64L * 1024 * 1024
    private const val MAX_PACKET = 8 * 1024 * 1024

    fun read(input: InputStream, extension: String): Int? {
        val data = DataInputStream(input.buffered(16 * 1024))
        return when (extension.uppercase(Locale.ROOT)) {
            "MP3" -> id3(data)
            "FLAC" -> flac(data)
            "OGG", "OPUS" -> ogg(data)
            "M4A", "MP4" -> atoms(data, Long.MAX_VALUE, 0)
            "WAV" -> wave(data)
            else -> throw IOException("Unsupported rating-tag format: $extension")
        }
    }

    fun popmStars(value: Int): Int {
        require(value in 0..255)
        return when {
            value == 0 -> 0
            value < 32 -> 1
            value < 96 -> 2
            value < 160 -> 3
            value < 224 -> 4
            else -> 5
        }
    }

    fun percentageStars(text: String): Int {
        val value = text.trim().trim('\u0000', '\uFEFF').trim().toDoubleOrNull()
            ?: throw IOException("Invalid embedded rating")
        if (!value.isFinite() || value !in 0.0..100.0 || (value > 0 && value < 10)) {
            throw IOException("Rating is not on MusicBee's 0-100 percentage scale")
        }
        return (value / 20).roundToInt().coerceIn(0, 5)
    }

    private fun id3(data: DataInputStream): Int? {
        val header = data.prefix(10)
        if (header.size < 3 || String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        if (header.size != 10) throw EOFException("Truncated ID3 header")
        val version = header[3].toInt() and 255
        if (version !in 2..4) throw IOException("Unsupported ID3 version $version")
        val flags = header[5].toInt() and 255
        val size = syncSafe(header.copyOfRange(6, 10)).toLong()
        if (size > MAX_TAG) throw IOException("ID3 metadata exceeds 64 MiB")
        if (version == 2 && flags and 0x40 != 0) throw IOException("Compressed ID3v2.2 is unsupported")
        val unsync = flags and 0x80 != 0
        if (unsync && version < 4) {
            if (size > MAX_PACKET) throw IOException("Unsynchronised ID3 metadata exceeds 8 MiB")
            val decoded = deUnsync(data.bytes(size.toInt()))
            return id3Frames(DataInputStream(ByteArrayInputStream(decoded)), decoded.size.toLong(), version, flags, false)
        }
        return id3Frames(data, size, version, flags, unsync)
    }

    private fun id3Frames(data: DataInputStream, length: Long, version: Int, flags: Int, tagUnsync: Boolean): Int? {
        var left = length
        if (version >= 3 && flags and 0x40 != 0) {
            if (left < 4) throw IOException("Truncated ID3 extended header")
            val size = if (version == 4) syncSafe(data.bytes(4)).toLong() else data.be32()
            val rest = if (version == 4) size - 4 else size
            if (rest < 0 || rest + 4 > left) throw IOException("Invalid ID3 extended header")
            data.skipExact(rest)
            left -= rest + 4
        }
        var rating: Int? = null
        var textRating: String? = null
        var ratingOwner = ""
        var priority = -1
        val headerSize = if (version == 2) 6 else 10
        while (left >= headerSize) {
            val header = data.bytes(headerSize)
            left -= headerSize
            val idLength = if (version == 2) 3 else 4
            val id = String(header, 0, idLength, Charsets.ISO_8859_1)
            if (header[0] == 0.toByte()) { data.skipExact(left); break }
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) throw IOException("Invalid ID3 frame name")
            val size = when (version) {
                2 -> ((header[3].toInt() and 255) shl 16) or ((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
                4 -> syncSafe(header.copyOfRange(4, 8))
                else -> beInt(header, 4)
            }.toLong()
            if (size < 0 || size > left) throw IOException("Invalid ID3 frame length")
            left -= size
            if (id !in setOf("POPM", "POP", "TXXX", "TXX")) { data.skipExact(size); continue }
            if (size > 65536) throw IOException("Oversized rating frame")
            val frameFlags = if (version == 2) 0 else ((header[8].toInt() and 255) shl 8) or (header[9].toInt() and 255)
            val encoded = if (version == 4) frameFlags and 0x0c != 0 else frameFlags and 0xc0 != 0
            if (encoded) throw IOException("Compressed/encrypted rating frames are unsupported")
            var payload = data.bytes(size.toInt())
            if (version == 4 && (tagUnsync || frameFlags and 2 != 0)) payload = deUnsync(payload)
            val grouped = if (version == 4) frameFlags and 0x40 != 0 else frameFlags and 0x20 != 0
            if (grouped) payload = payload.drop(1).toByteArray()
            if (version == 4 && frameFlags and 1 != 0) {
                if (payload.size < 4) throw IOException("Truncated rating data-length indicator")
                payload = payload.copyOfRange(4, payload.size)
            }
            if (id == "POPM" || id == "POP") {
                val end = payload.indexOf(0.toByte())
                if (end < 0 || end + 1 >= payload.size) throw IOException("Truncated POPM rating")
                val owner = String(payload, 0, end, Charsets.ISO_8859_1)
                val rank = when {
                    owner.contains("musicbee", true) -> 4
                    owner.equals("Windows Media Player 9 Series", true) -> 3
                    owner.isEmpty() -> 1
                    else -> 0
                }
                val value = popmStars(payload[end + 1].toInt() and 255)
                if (rank == priority && owner.equals(ratingOwner, true) && rating != value) throw IOException("Conflicting POPM tags")
                if (rank > priority) { rating = value; priority = rank; ratingOwner = owner }
            } else if (priority < 0) {
                if (payload.isEmpty()) throw IOException("Empty text rating frame")
                val charset = when (payload[0].toInt()) {
                    0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8
                    else -> throw IOException("Unknown text rating encoding")
                }
                val text = decode(payload.copyOfRange(1, payload.size), charset)
                val fields = text.split('\u0000', limit = 2)
                if (fields.size == 2 && fields[0].trim().equals("RATING", true)) textRating = fields[1]
            }
        }
        return rating ?: textRating?.let(::percentageStars)
    }

    private fun flac(data: DataInputStream): Int? {
        if (data.ascii(4) != "fLaC") throw IOException("Invalid FLAC header")
        var total = 0L
        repeat(512) {
            val type = data.readUnsignedByte()
            val size = (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
            total += size + 4
            if (total > MAX_TAG) throw IOException("FLAC metadata exceeds 64 MiB")
            val section = LimitedInput(data, size.toLong())
            val rating = if (type and 127 == 4) comments(DataInputStream(section)) else null
            if (rating != null) return rating
            DataInputStream(section).skipExact(section.remaining)
            if (type and 128 != 0) return null
        }
        throw IOException("Too many FLAC metadata blocks")
    }

    private fun comments(data: DataInputStream): Int? {
        val vendor = data.le32()
        if (vendor > MAX_TAG) throw IOException("Oversized Vorbis vendor")
        data.skipExact(vendor)
        val count = data.le32()
        if (count > 100_000) throw IOException("Too many Vorbis comments")
        var rating: Int? = null
        repeat(count.toInt()) {
            val size = data.le32()
            if (size > MAX_TAG) throw IOException("Oversized Vorbis comment")
            val prefix = data.bytes(minOf(size, 128).toInt())
            data.skipExact(size - prefix.size)
            val text = String(prefix, Charsets.UTF_8)
            if (text.startsWith("RATING=", true)) {
                if (size > 128) throw IOException("Oversized RATING value")
                val value = percentageStars(text.substringAfter('='))
                if (rating != null && rating != value) throw IOException("Conflicting RATING tags")
                rating = value
            }
        }
        return rating
    }

    private fun ogg(data: DataInputStream): Int? {
        val packet = ByteArrayOutputStream()
        var packets = 0
        var total = 0
        repeat(1024) {
            val header = data.prefix(27)
            if (header.isEmpty()) return null
            if (header.size != 27 || String(header, 0, 4, Charsets.ISO_8859_1) != "OggS") throw IOException("Invalid Ogg page")
            val segments = data.bytes(header[26].toInt() and 255)
            for (segment in segments) {
                val size = segment.toInt() and 255
                total += size
                if (total > MAX_PACKET) throw IOException("Ogg headers exceed 8 MiB")
                packet.write(data.bytes(size))
                if (size < 255) {
                    val bytes = packet.toByteArray()
                    val prefix = String(bytes, 0, minOf(8, bytes.size), Charsets.ISO_8859_1)
                    if (prefix.startsWith("OpusTags") || prefix.startsWith("\u0003vorbis")) {
                        val offset = if (prefix.startsWith("OpusTags")) 8 else 7
                        return comments(DataInputStream(ByteArrayInputStream(bytes, offset, bytes.size - offset)))
                    }
                    packet.reset()
                    if (++packets >= 3) return null
                }
            }
        }
        throw IOException("Ogg headers are too large")
    }

    private fun wave(data: DataInputStream): Int? {
        if (data.ascii(4) != "RIFF") throw IOException("Unsupported WAV container")
        var left = data.le32() - 4
        if (left < 0) throw IOException("Invalid RIFF length")
        if (data.ascii(4) != "WAVE") throw IOException("Invalid WAV header")
        while (left >= 8) {
            val type = data.ascii(4)
            val size = data.le32()
            left -= 8
            if (size > left) throw IOException("Invalid WAV chunk length")
            if (type.equals("id3 ", true)) return id3(DataInputStream(LimitedInput(data, size)))
            data.skipExact(size + size % 2)
            left -= size + size % 2
        }
        return null
    }

    private fun atoms(data: DataInputStream, length: Long, depth: Int): Int? {
        if (depth > 8) throw IOException("MP4 metadata nesting is too deep")
        var left = length
        repeat(10_000) {
            if (left < 8) return null
            val header = data.prefix(8)
            if (header.isEmpty() && length == Long.MAX_VALUE) return null
            if (header.size != 8) throw IOException("Truncated MP4 atom")
            var size = beInt(header, 0).toLong() and 0xffffffffL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            if (size == 1L) { size = data.readLong(); headerSize = 16 }
            if (size == 0L) {
                if (length == Long.MAX_VALUE && type == "mdat") return null
                size = left
            }
            if (size < headerSize || size > left) throw IOException("Invalid MP4 atom length")
            val section = LimitedInput(data, size - headerSize)
            val nested = DataInputStream(section)
            val rating = when (type) {
                "moov", "udta", "ilst" -> atoms(nested, section.remaining, depth + 1)
                "meta" -> { nested.skipExact(4); atoms(nested, section.remaining, depth + 1) }
                "----" -> freeform(nested, section.remaining)
                "rate" -> mp4Rate(nested, section.remaining)
                else -> null
            }
            if (rating != null) return rating
            nested.skipExact(section.remaining)
            left -= size
        }
        throw IOException("Too many MP4 atoms")
    }

    private fun freeform(data: DataInputStream, length: Long): Int? {
        var left = length
        var owner = ""
        var name = ""
        var value: ByteArray? = null
        var oversized = false
        while (left >= 8) {
            val size = data.be32()
            val type = data.ascii(4)
            if (size < 8 || size > left) throw IOException("Invalid MP4 freeform tag")
            val payload = size - 8
            if (type == "data") {
                if (payload < 8) throw IOException("Invalid MP4 data atom")
                data.skipExact(8)
                if (payload - 8 > 4096) { data.skipExact(payload - 8); oversized = true }
                else value = data.bytes((payload - 8).toInt())
            } else if (type in setOf("mean", "name")) {
                val prefix = 4
                if (payload < prefix || payload > 4096) throw IOException("Invalid MP4 rating value")
                data.skipExact(prefix.toLong())
                val text = decode(data.bytes((payload - prefix).toInt()), Charsets.UTF_8).trimEnd('\u0000')
                when (type) { "mean" -> owner = text; "name" -> name = text }
            } else data.skipExact(payload)
            left -= size
        }
        if (owner != "com.apple.iTunes" || !name.equals("RATING", true)) return null
        if (oversized) throw IOException("Oversized MP4 RATING value")
        return value?.let { percentageStars(decode(it, Charsets.UTF_8).trimEnd('\u0000')) }
    }

    private fun mp4Rate(data: DataInputStream, length: Long): Int? {
        var left = length
        while (left >= 8) {
            val size = data.be32()
            val type = data.ascii(4)
            if (size < 8 || size > left) throw IOException("Invalid MP4 rate atom")
            val payload = size - 8
            if (type == "data") {
                if (payload < 8 || payload > 4096) throw IOException("Invalid MP4 rating data")
                val encoding = data.readInt() and 0xffffff
                data.skipExact(4)
                val bytes = data.bytes((payload - 8).toInt())
                val text = when (encoding) {
                    0, 1 -> decode(bytes, Charsets.UTF_8).trimEnd('\u0000')
                    21, 22 -> {
                        if (bytes.size !in 1..8) throw IOException("Invalid integer rating")
                        bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }.toString()
                    }
                    else -> throw IOException("Unsupported MP4 rating encoding")
                }
                return percentageStars(text)
            }
            data.skipExact(payload)
            left -= size
        }
        return null
    }

    private fun decode(bytes: ByteArray, charset: Charset): String = try {
        charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) { throw IOException("Invalid rating text", error) }

    private fun deUnsync(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val value = bytes[index++]
            output.write(value.toInt())
            if (value == 0xff.toByte() && index < bytes.size && bytes[index] == 0.toByte()) index++
        }
        return output.toByteArray()
    }

    private fun syncSafe(bytes: ByteArray): Int {
        if (bytes.any { it.toInt() and 0x80 != 0 }) throw IOException("Invalid ID3 synchsafe integer")
        return bytes.fold(0) { result, byte -> (result shl 7) or (byte.toInt() and 127) }
    }
    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
}

private fun DataInputStream.bytes(size: Int): ByteArray = ByteArray(size).also(::readFully)
private fun DataInputStream.ascii(size: Int): String = String(bytes(size), Charsets.ISO_8859_1)
private fun DataInputStream.be32(): Long = readInt().toLong() and 0xffffffffL
private fun DataInputStream.le32(): Long = Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
private fun DataInputStream.prefix(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var count = 0
    while (count < size) {
        val read = read(bytes, count, size - count)
        if (read < 0) break
        count += read
    }
    return bytes.copyOf(count)
}
private fun DataInputStream.skipExact(size: Long) {
    if (size < 0) throw IOException("Negative metadata length")
    var left = size
    while (left > 1) {
        val skipped = skip(left - 1)
        if (skipped > 0) left -= skipped else { if (read() < 0) throw EOFException("Truncated metadata"); left-- }
    }
    if (left == 1L && read() < 0) throw EOFException("Truncated metadata")
}
private class LimitedInput(private val source: InputStream, var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = source.read()
        if (value >= 0) remaining--
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return -1
        val count = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }
    override fun skip(count: Long): Long = source.skip(minOf(count.coerceAtLeast(0), remaining)).also { remaining -= it }
}
