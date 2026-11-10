package app.snapcast.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class QualityLabelTest {
    private fun video(type: StreamType, variants: List<VideoVariant> = emptyList(), width: Int? = null, height: Int? = null) =
        DetectedVideo(id = "v", tabId = "t", url = "https://cdn.example.com/x", type = type, variants = variants, width = width, height = height)

    private fun variant(width: Int?, height: Int?, bandwidth: Long? = null) =
        VideoVariant(url = "https://cdn.example.com/${height ?: bandwidth}.m3u8", bandwidth = bandwidth, width = width, height = height)

    @Test
    fun `resolution class ignores orientation and aspect ratio`() {
        assertThat(VideoResolution.classOf(1920, 1080)).isEqualTo(1080)
        assertThat(VideoResolution.classOf(1080, 1920)).isEqualTo(1080)
        assertThat(VideoResolution.classOf(1920, 800)).isEqualTo(1080)
        assertThat(VideoResolution.classOf(1280, 544)).isEqualTo(720)
        assertThat(VideoResolution.classOf(1440, 1080)).isEqualTo(1080)
        assertThat(VideoResolution.classOf(4000, 2250)).isEqualTo(2160)
        assertThat(VideoResolution.classOf(640, 360)).isEqualTo(360)
        assertThat(VideoResolution.classOf(960, 540)).isEqualTo(540)
        assertThat(VideoResolution.classOf(null, 480)).isEqualTo(480)
        assertThat(VideoResolution.classOf(4096, 2160)).isEqualTo(2160)
        assertThat(VideoResolution.classOf(2048, 1080)).isEqualTo(1080)
        assertThat(VideoResolution.classOf(2048, 858)).isEqualTo(1080)
        assertThat(VideoResolution.classOf(4096, 1716)).isEqualTo(2160)
        assertThat(VideoResolution.classOf(1998, 1080)).isEqualTo(1080)
        // Up to 16:9 wide: the short side.
        assertThat(VideoResolution.classOf(1920, 1200)).isEqualTo(1200)
        assertThat(VideoResolution.classOf(1024, 768)).isEqualTo(768)
        assertThat(VideoResolution.classOf(1280, 960)).isEqualTo(960)
        assertThat(VideoResolution.classOf(960, 720)).isEqualTo(720)
        assertThat(VideoResolution.classOf(640, 640)).isEqualTo(640)
        assertThat(VideoResolution.classOf(640, 480)).isEqualTo(480)
        // Just over 16:9 because of rounding (ffmpeg scale=-2:H): still their height.
        assertThat(VideoResolution.classOf(1366, 768)).isEqualTo(768)
        assertThat(VideoResolution.classOf(1138, 640)).isEqualTo(640)
        assertThat(VideoResolution.classOf(712, 400)).isEqualTo(400)
        assertThat(VideoResolution.classOf(2134, 1200)).isEqualTo(1200)
        assertThat(VideoResolution.classOf(2560, 1080)).isEqualTo(1440)
        assertThat(VideoResolution.classOf(3840, 2160)).isEqualTo(2160)
        assertThat(VideoResolution.classOf(1280, null)).isNull()
        assertThat(VideoResolution.classOf(0, 0)).isNull()
    }

    @Test
    fun `adaptive stream is labelled by its best variant, never by the page player's size`() {
        val master = video(
            StreamType.HLS,
            variants = listOf(variant(640, 360, 800_000), variant(1920, 1080, 5_000_000), variant(1280, 720, 2_500_000)),
            width = 640,
            height = 360,
        )
        assertThat(master.qualityLabel).isEqualTo("1080p")
        assertThat(master.hasQualityRange).isTrue()
    }

    @Test
    fun `adaptive stream without known variants has no label`() {
        val pending = video(StreamType.HLS, width = 640, height = 360)
        assertThat(pending.qualityLabel).isNull()
        assertThat(pending.hasQualityRange).isFalse()
        assertThat(video(StreamType.DASH, height = 360).qualityLabel).isNull()
    }

    @Test
    fun `master without resolutions is labelled by its highest bitrate`() {
        val master = video(StreamType.HLS, variants = listOf(variant(null, null, 800_000), variant(null, null, 5_000_000)), height = 360)
        assertThat(master.qualityLabel).isEqualTo("5.0 Mbps")
        assertThat(master.hasQualityRange).isTrue()
    }

    @Test
    fun `bitrate label ignores the default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertThat(VideoVariant.formatBitrate(2_500_000)).isEqualTo("2.5 Mbps")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `single variant is a fixed quality`() {
        val single = video(StreamType.HLS, variants = listOf(variant(1280, 720, 2_500_000)))
        assertThat(single.qualityLabel).isEqualTo("720p")
        assertThat(single.hasQualityRange).isFalse()
    }

    @Test
    fun `progressive file is labelled by its own size class`() {
        assertThat(video(StreamType.MP4, width = 1080, height = 1920).qualityLabel).isEqualTo("1080p")
        assertThat(video(StreamType.MP4, width = 1920, height = 800).qualityLabel).isEqualTo("1080p")
        assertThat(video(StreamType.MP4).qualityLabel).isNull()
        assertThat(video(StreamType.MP4, width = 1920, height = 1080).hasQualityRange).isFalse()
    }

    @Test
    fun `portrait variant label uses the resolution class`() {
        assertThat(variant(1080, 1920).qualityLabel).isEqualTo("1080p")
    }
}
