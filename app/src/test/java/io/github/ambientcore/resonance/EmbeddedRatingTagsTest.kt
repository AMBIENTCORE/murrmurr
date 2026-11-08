package io.github.ambientcore.resonance

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException

class EmbeddedRatingTagsTest {
    @Test fun metadataReadsFlacDatesAndCatalogsWithoutDecodingAudioOrRatings() {
        val tags = comments("date=2026", "CATALOGNUMBER=CAT-012", "RATING=not a percentage",
            "METADATA_BLOCK_PICTURE=" + "A".repeat(1024 * 1024))
        val bytes = "fLaC".toByteArray() + byteArrayOf(0x84.toByte()) + uint24(tags.size) + tags
        val source = CountingInput(bytes)
        assertEquals(EmbeddedTrackMetadata("2026", "CAT-012"), EmbeddedRatingTags.readMetadata(source, "FLAC"))
        assertTrue("Metadata reads must skip picture payloads", source.bytesRead < 80 * 1024)
    }

    @Test fun metadataReadsId3YearsAndUserTextCatalogsIndependentlyOfRatings() {
        val year = frame("TYER", byteArrayOf(0) + "2024\u0000".toByteArray())
        val catalog = frame("TXXX", byteArrayOf(3) + "CATALOG NUMBER\u0000ID3-012".toByteArray())
        val rating = frame("TXXX", byteArrayOf(3) + "RATING\u0000invalid".toByteArray())
        assertEquals(EmbeddedTrackMetadata("2024", "ID3-012"),
            EmbeddedRatingTags.readMetadata(ByteArrayInputStream(id3(year + catalog + rating)), "MP3"))
    }

    @Test fun metadataReadsVorbisAndOpusDateComments() {
        val fields = comments("YEAR=0", "DATE=1998-01-02", "CATALOGNO=OGG-9")
        assertEquals(EmbeddedTrackMetadata("1998", "OGG-9"),
            EmbeddedRatingTags.readMetadata(ByteArrayInputStream(ogg("\u0003vorbis".toByteArray() + fields)), "OGG"))
        assertEquals(EmbeddedTrackMetadata("1998", "OGG-9"),
            EmbeddedRatingTags.readMetadata(ByteArrayInputStream(ogg("OpusTags".toByteArray() + fields)), "OPUS"))
    }

    @Test fun verifiedMusicBeeScalesMapToWholeStars() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), listOf(0, 1, 64, 128, 196, 255).map(EmbeddedRatingTags::popmStars))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), listOf("0", "20", "40", "60", "80", "100").map(EmbeddedRatingTags::percentageStars))
        assertEquals(5, EmbeddedRatingTags.percentageStars("90"))
        assertThrows(IOException::class.java) { EmbeddedRatingTags.percentageStars("0.8") }
        assertThrows(IOException::class.java) { EmbeddedRatingTags.percentageStars("NaN") }
        assertThrows(IOException::class.java) { EmbeddedRatingTags.percentageStars("255") }
    }

    @Test fun mp3VersionsThreeAndFourReadPopmWithoutImportingItsPlaycounter() {
        for (version in listOf(3, 4)) {
            val payload = "Windows Media Player 9 Series\u0000".toByteArray() + byteArrayOf(196.toByte(), 0, 0, 0, 99)
            assertEquals(4, read(id3(frame("POPM", payload, version), version), "MP3"))
        }
    }

    @Test fun legacyId3VersionTwoPopularimeterIsSupported() {
        val payload = byteArrayOf(0, 255.toByte())
        val frame = "POP".toByteArray() + byteArrayOf(0, 0, payload.size.toByte()) + payload
        assertEquals(5, read(id3(frame, 2), "MP3"))
    }

    @Test fun windowsMediaPlayerOwnerTakesPriorityOverOtherPopmFrames() {
        val other = frame("POPM", "other\u0000".toByteArray() + byteArrayOf(1))
        val wmp = frame("POPM", "Windows Media Player 9 Series\u0000".toByteArray() + byteArrayOf(255.toByte()))
        assertEquals(5, read(id3(other + wmp), "MP3"))
    }

    @Test fun unrelatedAlbumRatingsAreNotUsedAsTrackRatings() {
        val album = frame("TXXX", byteArrayOf(3) + "ALBUM RATING\u0000100".toByteArray())
        assertNull(read(id3(album), "MP3"))
    }

    @Test fun standardPopmTakesPriorityOverAmbiguousTextRatingsRegardlessOfFrameOrder() {
        val text = frame("TXXX", byteArrayOf(3) + "RATING\u00005".toByteArray())
        val popm = frame("POPM", "Windows Media Player 9 Series\u0000".toByteArray() + byteArrayOf(255.toByte()))
        assertEquals(5, read(id3(text + popm), "MP3"))
        assertEquals(5, read(id3(popm + text), "MP3"))
        assertThrows(IOException::class.java) {
            read(id3(popm + frame("POPM", "Windows Media Player 9 Series\u0000".toByteArray() + byteArrayOf(1))), "MP3")
        }
    }

    @Test fun utf16TextRatingAndExtendedHeadersAreSupported() {
        val payload = byteArrayOf(1) + "RATING\u000080\u0000".toByteArray(Charsets.UTF_16)
        val extended = be(6) + ByteArray(6)
        assertEquals(4, read(id3(extended + frame("TXXX", payload), flags = 0x40), "MP3"))
    }

    @Test fun id3UnsynchronisationIsDecoded() {
        val payload = byteArrayOf(0, 255.toByte(), 0)
        val raw = frame("POPM", payload)
        val encoded = ByteArrayOutputStream().apply {
            raw.forEach { byte -> write(byte.toInt()); if (byte == 255.toByte()) write(0) }
        }.toByteArray()
        assertEquals(5, read(id3(encoded, flags = 0x80), "MP3"))
    }

    @Test fun embeddedArtworkIsSeekSkippedRatherThanReadInFull() {
        val image = frame("APIC", ByteArray(4 * 1024 * 1024))
        val rating = frame("POPM", byteArrayOf(0, 128.toByte()))
        val source = CountingInput(id3(image + rating))
        assertEquals(3, EmbeddedRatingTags.read(source, "MP3"))
        assertTrue("Should not read the entire embedded image: ${source.bytesRead}", source.bytesRead < 64 * 1024)
        assertTrue(source.skipped > 3 * 1024 * 1024)
    }

    @Test fun flacPercentageRatingsSkipPictureBlocksAndLargePictureComments() {
        val picture = byteArrayOf(6) + uint24(1024 * 1024) + ByteArray(1024 * 1024)
        val comments = comments("METADATA_BLOCK_PICTURE=" + "A".repeat(1024 * 1024), "rating=60")
        val bytes = "fLaC".toByteArray() + picture + byteArrayOf(0x84.toByte()) + uint24(comments.size) + comments
        val source = CountingInput(bytes)
        assertEquals(3, EmbeddedRatingTags.read(source, "FLAC"))
        assertTrue(source.bytesRead < 80 * 1024)
    }

    @Test fun vorbisAndOpusCommentPacketsAreSupported() {
        assertEquals(5, read(ogg("\u0003vorbis".toByteArray() + comments("RATING=100")), "OGG"))
        assertEquals(2, read(ogg("OpusTags".toByteArray() + comments("RATING=40")), "OPUS"))
    }

    @Test fun mp4FreeformAndNativeRateAreSupportedButParentalAdvisoryIsIgnored() {
        val free = atom("----", atom("mean", ByteArray(4) + "com.apple.iTunes".toByteArray()) +
            atom("name", ByteArray(4) + "RATING".toByteArray()) + atom("data", be(1) + ByteArray(4) + "80".toByteArray()))
        assertEquals(4, read(mp4(free), "M4A"))
        assertEquals(5, read(mp4(atom("rate", atom("data", be(21) + ByteArray(4) + be(100)))), "M4A"))
        assertNull(read(mp4(atom("rtng", atom("data", be(21) + ByteArray(4) + byteArrayOf(1)))), "M4A"))
    }

    @Test fun unrelatedLargeOrBinaryMp4FreeformDataDoesNotBreakRatingImport() {
        val unrelated = atom("----", atom("mean", ByteArray(4) + "com.apple.iTunes".toByteArray()) +
            atom("name", ByteArray(4) + "LYRICS".toByteArray()) + atom("data", ByteArray(8) + ByteArray(8000)))
        val rating = atom("rate", atom("data", be(1) + ByteArray(4) + "20".toByteArray()))
        assertEquals(1, read(mp4(unrelated + rating), "M4A"))
    }

    @Test fun wavEmbeddedId3CanFollowTheAudioChunk() {
        val id3 = id3(frame("POPM", byteArrayOf(0, 64)))
        val chunks = chunk("data", ByteArray(1024)) + chunk("id3 ", id3)
        assertEquals(2, read("RIFF".toByteArray() + le(chunks.size + 4) + "WAVE".toByteArray() + chunks, "WAV"))
    }

    @Test fun untaggedFilesRemainUnratedAndZeroIsAnExplicitClear() {
        assertNull(read(byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte()) + ByteArray(100), "MP3"))
        assertEquals(0, read(id3(frame("POPM", byteArrayOf(0, 0))), "MP3"))
        assertEquals(0, read(flac(comments("RATING=0")), "FLAC"))
        assertNull(read(flac(comments("TITLE=Track")), "FLAC"))
    }

    @Test fun malformedOrConflictingMetadataRaisesErrorsRatherThanGuessing() {
        assertThrows(IOException::class.java) { read("ID3".toByteArray(), "MP3") }
        assertThrows(IOException::class.java) { read(id3(frame("POPM", byteArrayOf(1, 2))), "MP3") }
        assertThrows(IOException::class.java) { read(flac(comments("RATING=20", "RATING=100")), "FLAC") }
        assertThrows(IOException::class.java) { read("RIFF".toByteArray() + le(0) + "WAVE".toByteArray(), "WAV") }
        assertThrows(IOException::class.java) { read(be(100) + "moov".toByteArray(), "M4A") }
        assertThrows(IOException::class.java) { read(id3(ByteArray(10)).copyOf(15), "MP3") }
    }

    private fun read(bytes: ByteArray, extension: String) = EmbeddedRatingTags.read(ByteArrayInputStream(bytes), extension)
    private fun be(value: Int): ByteArray = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(value) }.toByteArray()
    private fun le(value: Int): ByteArray = be(Integer.reverseBytes(value))
    private fun uint24(value: Int) = byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun sync(value: Int) = byteArrayOf((value shr 21 and 127).toByte(), (value shr 14 and 127).toByte(),
        (value shr 7 and 127).toByte(), (value and 127).toByte())
    private fun frame(id: String, data: ByteArray, version: Int = 3) =
        id.toByteArray() + (if (version == 4) sync(data.size) else be(data.size)) + ByteArray(2) + data
    private fun id3(data: ByteArray, version: Int = 3, flags: Int = 0) =
        "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, flags.toByte()) + sync(data.size) + data
    private fun comments(vararg entries: String): ByteArray = le(0) + le(entries.size) +
        entries.fold(ByteArray(0)) { all, entry -> entry.toByteArray().let { all + le(it.size) + it } }
    private fun flac(data: ByteArray) = "fLaC".toByteArray() + byteArrayOf(0x84.toByte()) + uint24(data.size) + data
    private fun atom(type: String, data: ByteArray) = be(data.size + 8) + type.toByteArray() + data
    private fun mp4(data: ByteArray) = atom("ftyp", "M4A ".toByteArray()) +
        atom("moov", atom("udta", atom("meta", ByteArray(4) + atom("ilst", data))))
    private fun chunk(type: String, data: ByteArray) = type.toByteArray() + le(data.size) + data +
        if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
    private fun ogg(packet: ByteArray): ByteArray {
        val segments = mutableListOf<Byte>()
        var left = packet.size
        while (left >= 255) { segments += 255.toByte(); left -= 255 }
        segments += left.toByte()
        return "OggS".toByteArray() + ByteArray(22) + byteArrayOf(segments.size.toByte()) + segments.toByteArray() + packet
    }
    private class CountingInput(bytes: ByteArray) : FilterInputStream(ByteArrayInputStream(bytes)) {
        var bytesRead = 0
        var skipped = 0L
        override fun read(): Int = `in`.read().also { if (it >= 0) bytesRead++ }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            `in`.read(bytes, offset, length).also { if (it > 0) bytesRead += it }
        override fun skip(count: Long): Long = `in`.skip(count).also { skipped += it }
    }
}
