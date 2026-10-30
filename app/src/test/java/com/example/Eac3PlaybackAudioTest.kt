package com.example

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
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
    fun ffmpegAudioRenderer_canBeInstantiatedWithAudioSink() {
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

        val handler = Handler(Looper.getMainLooper())
        val renderer = FfmpegAudioRenderer(handler, null, audioSink)

        assertNotNull(renderer)
        assertEquals("FfmpegAudioRenderer", renderer.name)
        assertEquals(C.TRACK_TYPE_AUDIO, renderer.trackType)
    }

    @Test
    fun renderersPipeline_containsBothMediaCodecAndFfmpegAudioRenderers() {
        val out = ArrayList<Renderer>()
        val handler = Handler(Looper.getMainLooper())

        class CustomTestRenderersFactory(ctx: Context) : DefaultRenderersFactory(ctx) {
            fun createAudioRenderers(
                sink: AudioSink,
                h: Handler,
                listener: AudioRendererEventListener,
                renderers: ArrayList<Renderer>
            ) {
                buildAudioRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_ON,
                    MediaCodecSelector.DEFAULT,
                    true,
                    sink,
                    h,
                    listener,
                    renderers
                )
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                val selectiveMediaCodecSelector = MediaCodecSelector { mimeType, reqSec, reqTun ->
                    if (PlayerManager.isEac3MimeType(mimeType)) {
                        emptyList()
                    } else {
                        mediaCodecSelector.getDecoderInfos(mimeType, reqSec, reqTun)
                    }
                }

                out.add(
                    MediaCodecAudioRenderer(
                        context,
                        selectiveMediaCodecSelector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        audioSink
                    )
                )

                out.add(
                    FfmpegAudioRenderer(
                        eventHandler,
                        eventListener,
                        audioSink
                    )
                )
            }
        }

        val factory = CustomTestRenderersFactory(context)
        factory.createAudioRenderers(
            DefaultAudioSink.Builder(context).build(),
            handler,
            object : AudioRendererEventListener {},
            out
        )

        assertEquals("Must build 2 audio renderers (MediaCodec + FFmpeg)", 2, out.size)
        assertTrue("Renderer 0 must be MediaCodecAudioRenderer", out[0] is MediaCodecAudioRenderer)
        assertTrue("Renderer 1 must be FfmpegAudioRenderer", out[1] is FfmpegAudioRenderer)
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
}
