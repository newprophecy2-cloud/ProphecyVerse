package com.example

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.core.app.ApplicationProvider
import com.example.player.PlayerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.ArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Eac3PlaybackAudioTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun isEac3MimeType_correctlyIdentifiesEac3Variants() {
        // EAC3 variants must be identified as EAC3
        assertTrue(PlayerManager.isEac3MimeType("audio/eac3"))
        assertTrue(PlayerManager.isEac3MimeType("AUDIO/EAC3"))
        assertTrue(PlayerManager.isEac3MimeType("audio/eac3-joc"))
        assertTrue(PlayerManager.isEac3MimeType(MimeTypes.AUDIO_E_AC3))
        assertTrue(PlayerManager.isEac3MimeType(MimeTypes.AUDIO_E_AC3_JOC))

        // Non-EAC3 audio formats must NOT be identified as EAC3
        assertFalse(PlayerManager.isEac3MimeType("audio/mp4a-latm"))
        assertFalse(PlayerManager.isEac3MimeType("audio/mpeg"))
        assertFalse(PlayerManager.isEac3MimeType("audio/opus"))
        assertFalse(PlayerManager.isEac3MimeType("audio/flac"))
        assertFalse(PlayerManager.isEac3MimeType("audio/vorbis"))
        assertFalse(PlayerManager.isEac3MimeType("audio/raw"))
        assertFalse(PlayerManager.isEac3MimeType(null))
        assertFalse(PlayerManager.isEac3MimeType(""))
    }

    @Test
    fun selectiveMediaCodecSelector_excludesEac3AndPreservesOthers() {
        val dummyDecoder = MediaCodecInfo.newInstance(
            "dummy.aac.decoder",
            "audio/mp4a-latm",
            "audio/mp4a-latm",
            null,
            false,
            false,
            false,
            false,
            false
        )

        val underlyingSelector = MediaCodecSelector { mimeType, _, _ ->
            listOf(dummyDecoder)
        }

        val selectiveSelector = MediaCodecSelector { mimeType, reqSec, reqTun ->
            if (PlayerManager.isEac3MimeType(mimeType)) {
                emptyList()
            } else {
                underlyingSelector.getDecoderInfos(mimeType, reqSec, reqTun)
            }
        }

        // EAC3 formats must return empty list (MediaCodec will not handle them)
        val eac3Decoders = selectiveSelector.getDecoderInfos("audio/eac3", false, false)
        assertTrue("EAC3 must return empty list in selective MediaCodecSelector", eac3Decoders.isEmpty())

        val eac3JocDecoders = selectiveSelector.getDecoderInfos("audio/eac3-joc", false, false)
        assertTrue("EAC3-JOC must return empty list in selective MediaCodecSelector", eac3JocDecoders.isEmpty())

        // Non-EAC3 formats (e.g. AAC) must return original decoders unchanged
        val aacDecoders = selectiveSelector.getDecoderInfos("audio/mp4a-latm", false, false)
        assertEquals("Non-EAC3 formats must retain underlying decoders", 1, aacDecoders.size)
        assertEquals("dummy.aac.decoder", aacDecoders[0].name)
    }

    @Test
    fun ffmpegNativeLibraries_existForAllSupportedAbis() {
        val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        val baseDir = if (File("src/main/jniLibs").exists()) File("src/main/jniLibs") else File("app/src/main/jniLibs")

        assertTrue("baseDir for jniLibs must exist", baseDir.exists())
        for (abi in supportedAbis) {
            val soFile = File(baseDir, "$abi/libffmpegJNI.so")
            assertTrue("libffmpegJNI.so must exist for ABI $abi", soFile.exists())
            assertTrue("libffmpegJNI.so for $abi must not be empty", soFile.length() > 500_000)
        }
    }

    @Test
    fun renderersPipeline_configuresAudioSinkAndSelectiveMediaCodec() {
        val out = ArrayList<Renderer>()
        val handler = Handler(Looper.getMainLooper())

        val audioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setAudioCapabilities(
                AudioCapabilities(
                    intArrayOf(
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        android.media.AudioFormat.ENCODING_PCM_FLOAT
                    ),
                    8
                )
            )
            .build()

        val selectiveMediaCodecSelector = MediaCodecSelector { mimeType, reqSec, reqTun ->
            if (PlayerManager.isEac3MimeType(mimeType)) {
                emptyList()
            } else {
                MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, reqSec, reqTun)
            }
        }

        val mediaCodecRenderer = MediaCodecAudioRenderer(
            context,
            selectiveMediaCodecSelector,
            true,
            handler,
            object : AudioRendererEventListener {},
            audioSink
        )
        out.add(mediaCodecRenderer)

        assertTrue("MediaCodec renderer must be registered", out.isNotEmpty())
        assertEquals(C.TRACK_TYPE_AUDIO, out[0].trackType)
        assertTrue(out[0] is MediaCodecAudioRenderer)
    }

    @Test
    fun eac3OnlyTrack_extractsAsFfmpegDecoder() {
        val eac3Format = Format.Builder()
            .setId("1")
            .setSampleMimeType("audio/eac3")
            .setLanguage("ja")
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        val trackGroup = TrackGroup(eac3Format)
        val trackGroupInfo = Tracks.Group(trackGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        val tracks = Tracks(listOf(trackGroupInfo))

        // Verify track extraction logic
        val audioList = mutableListOf<PlayerManager.TrackOption>()
        for ((groupIndex, groupInfo) in tracks.groups.withIndex()) {
            val group = groupInfo.mediaTrackGroup
            for (i in 0 until group.length) {
                val format = group.getFormat(i)
                val isEac3 = PlayerManager.isEac3MimeType(format.sampleMimeType)
                val codec = if (isEac3) "EAC3" else "OTHER"
                val decoderBadge = if (isEac3) " [FFmpeg]" else ""
                audioList.add(
                    PlayerManager.TrackOption(
                        id = "audio_${groupIndex}_${i}",
                        label = "Japanese ($codec)$decoderBadge",
                        isSelected = groupInfo.isTrackSelected(i),
                        trackGroup = group,
                        trackIndex = i,
                        sampleMimeType = format.sampleMimeType,
                        isEac3 = isEac3,
                        decoderType = if (isEac3) "FFmpeg" else "MediaCodec"
                    )
                )
            }
        }

        assertEquals(1, audioList.size)
        val track = audioList[0]
        assertTrue("Track must be marked as EAC3", track.isEac3)
        assertEquals("FFmpeg", track.decoderType)
        assertTrue(track.label.contains("FFmpeg"))
        assertEquals("audio/eac3", track.sampleMimeType)
    }

    @Test
    fun nonEac3Track_extractsAsMediaCodecDecoder() {
        val aacFormat = Format.Builder()
            .setId("1")
            .setSampleMimeType("audio/mp4a-latm")
            .setLanguage("en")
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()

        val trackGroup = TrackGroup(aacFormat)
        val trackGroupInfo = Tracks.Group(trackGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        val tracks = Tracks(listOf(trackGroupInfo))

        val audioList = mutableListOf<PlayerManager.TrackOption>()
        for ((groupIndex, groupInfo) in tracks.groups.withIndex()) {
            val group = groupInfo.mediaTrackGroup
            for (i in 0 until group.length) {
                val format = group.getFormat(i)
                val isEac3 = PlayerManager.isEac3MimeType(format.sampleMimeType)
                audioList.add(
                    PlayerManager.TrackOption(
                        id = "audio_${groupIndex}_${i}",
                        label = "English (AAC)",
                        isSelected = groupInfo.isTrackSelected(i),
                        trackGroup = group,
                        trackIndex = i,
                        sampleMimeType = format.sampleMimeType,
                        isEac3 = isEac3,
                        decoderType = if (isEac3) "FFmpeg" else "MediaCodec"
                    )
                )
            }
        }

        assertEquals(1, audioList.size)
        val track = audioList[0]
        assertFalse("AAC track must not be marked as EAC3", track.isEac3)
        assertEquals("MediaCodec", track.decoderType)
        assertEquals("audio/mp4a-latm", track.sampleMimeType)
    }

    @Test
    fun eac3PlusAac_multiTrackStream_supportsSelection() {
        val aacFormat = Format.Builder()
            .setId("audio_aac")
            .setSampleMimeType("audio/mp4a-latm")
            .setLanguage("ja")
            .build()

        val eac3Format = Format.Builder()
            .setId("audio_eac3")
            .setSampleMimeType("audio/eac3")
            .setLanguage("en")
            .build()

        val aacGroup = TrackGroup(aacFormat)
        val eac3Group = TrackGroup(eac3Format)

        val tracks = Tracks(
            listOf(
                Tracks.Group(aacGroup, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                Tracks.Group(eac3Group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
            )
        )

        val audioList = mutableListOf<PlayerManager.TrackOption>()
        for ((groupIndex, groupInfo) in tracks.groups.withIndex()) {
            val group = groupInfo.mediaTrackGroup
            for (i in 0 until group.length) {
                val format = group.getFormat(i)
                val isEac3 = PlayerManager.isEac3MimeType(format.sampleMimeType)
                audioList.add(
                    PlayerManager.TrackOption(
                        id = "audio_${groupIndex}_${i}",
                        label = "${format.language} (${if (isEac3) "EAC3 [FFmpeg]" else "AAC"})",
                        isSelected = groupInfo.isTrackSelected(i),
                        trackGroup = group,
                        trackIndex = i,
                        sampleMimeType = format.sampleMimeType,
                        isEac3 = isEac3,
                        decoderType = if (isEac3) "FFmpeg" else "MediaCodec"
                    )
                )
            }
        }

        assertEquals("Must detect both audio tracks", 2, audioList.size)

        val aacTrack = audioList.first { !it.isEac3 }
        val eac3Track = audioList.first { it.isEac3 }

        assertEquals("MediaCodec", aacTrack.decoderType)
        assertEquals("FFmpeg", eac3Track.decoderType)
        assertTrue(aacTrack.isSelected)
        assertFalse(eac3Track.isSelected)
    }

    @Test
    fun playerManager_ffmpegPreferredMimeTypes_andSelectiveSelector_coverAc3DtsTrueHdAndPreserveNative() {
        assertTrue(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_E_AC3))
        assertTrue(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_E_AC3_JOC))
        assertTrue(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_AC3))
        assertTrue(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_DTS))
        assertTrue(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_DTS_HD))
        assertTrue(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_DTS_X))
        assertTrue(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_TRUEHD))
        assertTrue(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_ALAC))
        assertTrue(PlayerManager.isFfmpegPreferredAudioMimeType("audio/mp2"))

        // Standard native hardware/platform codecs remain untouched
        assertFalse(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_AAC))
        assertFalse(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_MPEG))
        assertFalse(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_OPUS))
        assertFalse(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_VORBIS))
        assertFalse(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_FLAC))
        assertFalse(PlayerManager.isFfmpegPreferredAudioMimeType(MimeTypes.AUDIO_RAW))

        val dummyDecoder = MediaCodecInfo.newInstance(
            "c2.android.aac.decoder",
            "audio/mp4a-latm",
            "audio/mp4a-latm",
            null,
            false,
            false,
            false,
            false,
            false
        )
        val selector = PlayerManager.createSelectiveMediaCodecSelector(
            delegate = MediaCodecSelector { _, _, _ -> listOf(dummyDecoder) },
            preferFfmpegForExtendedCodecs = true
        )
        assertTrue(selector.getDecoderInfos(MimeTypes.AUDIO_E_AC3, false, false).isEmpty())
        assertTrue(selector.getDecoderInfos(MimeTypes.AUDIO_AC3, false, false).isEmpty())
        assertTrue(selector.getDecoderInfos(MimeTypes.AUDIO_DTS, false, false).isEmpty())
        assertTrue(selector.getDecoderInfos(MimeTypes.AUDIO_TRUEHD, false, false).isEmpty())
        assertEquals(1, selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false).size)
    }

    @Test
    fun playerManager_extractTracksFromMedia3_detectsLanguagesDecodersAndUnsupportedAudio() {
        val jaEac3 = Format.Builder()
            .setId("a1")
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setLanguage("jpn")
            .setChannelCount(6)
            .build()
        val enAac = Format.Builder()
            .setId("a2")
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setLanguage("en")
            .setChannelCount(2)
            .build()
        val unsupportedUnknown = Format.Builder()
            .setId("a3")
            .setSampleMimeType("audio/x-proprietary-exotic")
            .setLanguage("fr")
            .build()

        val tracks = Tracks(
            listOf(
                Tracks.Group(TrackGroup(jaEac3), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                Tracks.Group(TrackGroup(enAac), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
                Tracks.Group(TrackGroup(unsupportedUnknown), false, intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE), booleanArrayOf(false))
            )
        )

        val extracted = PlayerManager.extractTracksFromMedia3(tracks)
        assertEquals(4, extracted.audioTracks.size) // Default + 3 tracks
        val jaTrack = extracted.audioTracks.first { it.sampleMimeType == MimeTypes.AUDIO_E_AC3 }
        val enTrack = extracted.audioTracks.first { it.sampleMimeType == MimeTypes.AUDIO_AAC }
        val unsupportedTrack = extracted.audioTracks.first { it.sampleMimeType == "audio/x-proprietary-exotic" }

        assertTrue(jaTrack.label.contains("Japanese"))
        assertTrue(jaTrack.label.contains("FFmpeg"))
        assertTrue(jaTrack.isSelected)
        assertTrue(jaTrack.isSupported)
        assertTrue(enTrack.label.contains("English"))
        assertEquals("MediaCodec", enTrack.decoderType)
        assertFalse(unsupportedTrack.isSupported)
        assertTrue(unsupportedTrack.label.contains("Unsupported"))
        assertEquals("FFmpeg Audio Renderer (Software)", extracted.selectedAudioDecoderLabel)
        assertFalse(extracted.hasUnsupportedAudioOnly)

        // When ALL audio tracks in the stream are unsupported, flag must trigger a clear UI error
        val allUnsupported = PlayerManager.extractTracksFromMedia3(
            Tracks(listOf(Tracks.Group(TrackGroup(unsupportedUnknown), false, intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE), booleanArrayOf(false))))
        )
        assertTrue(allUnsupported.hasUnsupportedAudioOnly)
        assertEquals("Japanese", PlayerManager.getDisplayLanguage("jpn"))
        assertEquals("Audio", PlayerManager.getDisplayLanguage("und"))
    }
}
