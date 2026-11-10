package app.snapcast.core.media

import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.util.UrlUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DashInspectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val manifestUrl = "https://cdn.example.com/dash/movie/manifest.mpd?token=1"

    private val staticMpd = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1H2M3.5S"
             minBufferTime="PT2S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
          <BaseURL>media/</BaseURL>
          <Period id="1">
            <AdaptationSet contentType="video" mimeType="video/mp4" segmentAlignment="true">
              <Representation id="v360" bandwidth="800000" width="640" height="360" codecs="avc1.4d401e" frameRate="30000/1001"/>
              <Representation id="v1080" bandwidth="5000000" width="1920" height="1080" codecs="avc1.640028" frameRate="60"/>
              <Representation id="v720" bandwidth="2500000" width="1280" height="720" codecs="avc1.4d401f"/>
            </AdaptationSet>
            <AdaptationSet mimeType="audio/mp4" lang="en">
              <Representation id="a1" bandwidth="128000" codecs="mp4a.40.2"/>
            </AdaptationSet>
            <AdaptationSet contentType="text" mimeType="text/vtt" lang="de">
              <Label>Deutsch</Label>
              <BaseURL>subs/</BaseURL>
              <Representation id="sub-de" bandwidth="256">
                <BaseURL>de.vtt</BaseURL>
              </Representation>
            </AdaptationSet>
            <AdaptationSet contentType="text" lang="en">
              <Representation id="sub-en" bandwidth="256" mimeType="application/x-subrip">
                <BaseURL>https://subs.example.org/en.srt</BaseURL>
              </Representation>
            </AdaptationSet>
            <AdaptationSet contentType="text" mimeType="application/mp4" codecs="stpp">
              <Representation id="sub-segmented" bandwidth="256">
                <SegmentTemplate media="text_${'$'}Number${'$'}.mp4" initialization="text_init.mp4"/>
              </Representation>
            </AdaptationSet>
            <AdaptationSet contentType="image" mimeType="image/jpeg">
              <Representation id="thumbs" bandwidth="1000" width="320" height="180"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun `static manifest duration and representations`() {
        val info = DashInspector.inspect(staticMpd, manifestUrl)!!
        assertThat(info.isLive).isFalse()
        assertThat(info.durationSec).isWithin(1e-9).of(3723.5)
        assertThat(info.drm).isNull()
        assertThat(info.representations.map { it.height }).containsExactly(1080, 720, 360).inOrder()
        assertThat(info.representations.map { it.url }.toSet()).containsExactly(manifestUrl)

        val sd = info.representations.last()
        assertThat(sd.width).isEqualTo(640)
        assertThat(sd.bandwidth).isEqualTo(800_000L)
        assertThat(sd.codecs).isEqualTo("avc1.4d401e")
        assertThat(sd.frameRate).isWithin(0.001).of(29.97)
        assertThat(info.representations.first().frameRate).isEqualTo(60.0)
        assertThat(info.representations[1].frameRate).isNull()
    }

    @Test
    fun `text adaptation sets with single files become subtitles`() {
        val info = DashInspector.inspect(staticMpd, manifestUrl)!!
        assertThat(info.subtitles).hasSize(2)
        val de = info.subtitles[0]
        assertThat(de.url).isEqualTo("https://cdn.example.com/dash/movie/media/subs/de.vtt")
        assertThat(de.id).isEqualTo(UrlUtils.stableId(de.url))
        assertThat(de.language).isEqualTo("de")
        assertThat(de.label).isEqualTo("Deutsch")
        assertThat(de.format).isEqualTo(SubtitleFormat.VTT)
        val en = info.subtitles[1]
        assertThat(en.url).isEqualTo("https://subs.example.org/en.srt")
        assertThat(en.format).isEqualTo(SubtitleFormat.SRT)
        assertThat(en.language).isEqualTo("en")
    }

    @Test
    fun `subtitles with non-network URLs are dropped`() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period>
                <AdaptationSet contentType="text" mimeType="text/vtt" lang="en">
                  <Representation id="file" bandwidth="256">
                    <BaseURL>file:///data/data/app.snapcast/shared_prefs/settings.vtt</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet contentType="text" mimeType="text/vtt" lang="fr">
                  <Representation id="content" bandwidth="256">
                    <BaseURL>content://app.snapcast.browser.fileprovider/downloads/fr.vtt</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet contentType="text" mimeType="text/vtt" lang="de">
                  <Representation id="http" bandwidth="256">
                    <BaseURL>de.vtt</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val info = DashInspector.inspect(mpd, manifestUrl)!!
        assertThat(info.subtitles.map { it.url }).containsExactly("https://cdn.example.com/dash/movie/de.vtt")
    }

    @Test
    fun `dynamic manifest is live`() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="2026-01-01T00:00:00Z"
                 minimumUpdatePeriod="PT2S">
              <Period id="p0" start="PT0S">
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="1" bandwidth="1000000" width="1280" height="720"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val info = DashInspector.inspect(mpd, manifestUrl)!!
        assertThat(info.isLive).isTrue()
        assertThat(info.durationSec).isNull()
        assertThat(info.representations).hasSize(1)
    }

    @Test
    fun `duration falls back to the sum of period durations`() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period duration="PT30S"><AdaptationSet mimeType="video/mp4"><Representation id="1" bandwidth="1" height="480"/></AdaptationSet></Period>
              <Period duration="PT1M"><AdaptationSet mimeType="video/mp4"><Representation id="1" bandwidth="1" height="480"/></AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val info = DashInspector.inspect(mpd, manifestUrl)!!
        assertThat(info.durationSec).isEqualTo(90.0)
        assertThat(info.representations).hasSize(1)
    }

    @Test
    fun `video detection uses representation mime type codecs and content component`() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet>
                  <Representation id="v" mimeType="video/webm" bandwidth="900000" width="854" height="480" codecs="vp9"/>
                  <Representation id="a" mimeType="audio/webm" bandwidth="96000" codecs="opus"/>
                </AdaptationSet>
                <AdaptationSet>
                  <ContentComponent contentType="video"/>
                  <Representation id="cc" bandwidth="400000" width="426" height="240"/>
                </AdaptationSet>
                <AdaptationSet>
                  <Representation id="codecs-only" bandwidth="300000" codecs="hev1.1.6.L93.B0" height="144"/>
                </AdaptationSet>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <EssentialProperty schemeIdUri="http://dashif.org/guidelines/trickmode" value="1"/>
                  <Representation id="trick" bandwidth="10000" width="320" height="180"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val info = DashInspector.inspect(mpd, manifestUrl)!!
        assertThat(info.representations.map { it.height }).containsExactly(480, 240, 144).inOrder()
        assertThat(info.representations.first().codecs).isEqualTo("vp9")
    }

    @Test
    fun `namespace prefixes are ignored`() {
        val mpd = """
            <mpd:MPD xmlns:mpd="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S">
              <mpd:Period>
                <mpd:AdaptationSet contentType="video">
                  <mpd:Representation id="1" bandwidth="100" width="1280" height="720"/>
                </mpd:AdaptationSet>
              </mpd:Period>
            </mpd:MPD>
        """.trimIndent()
        val info = DashInspector.inspect(mpd, manifestUrl)!!
        assertThat(info.durationSec).isEqualTo(10.0)
        assertThat(info.representations.single().height).isEqualTo(720)
    }

    private fun protectedMpd(vararg schemes: String): String {
        val protections = schemes.joinToString("\n") { "<ContentProtection schemeIdUri=\"$it\" value=\"x\"/>" }
        return """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" type="static">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  $protections
                  <Representation id="1" bandwidth="100" height="720"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
    }

    @Test
    fun `ContentProtection marks DRM with the key system`() {
        val widevine = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
        val playready = "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"
        val fairplay = "urn:uuid:94ce86fb-07ff-4f43-adb8-93d2fa968ca2"
        val clearkey = "urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e"
        val cenc = "urn:mpeg:dash:mp4protection:2011"

        val wv = DashInspector.inspect(protectedMpd(cenc, widevine, playready), manifestUrl)!!.drm!!
        assertThat(wv.system).isEqualTo("Widevine")
        assertThat(wv.reason).isEqualTo("DASH ContentProtection")
        assertThat(DashInspector.inspect(protectedMpd(cenc, playready.uppercase()), manifestUrl)!!.drm!!.system).isEqualTo("PlayReady")
        assertThat(DashInspector.inspect(protectedMpd(fairplay), manifestUrl)!!.drm!!.system).isEqualTo("FairPlay")
        assertThat(DashInspector.inspect(protectedMpd(clearkey), manifestUrl)!!.drm!!.system).isEqualTo("ClearKey")
        assertThat(DashInspector.inspect(protectedMpd(cenc), manifestUrl)!!.drm!!.system).isEqualTo("CENC")
        assertThat(DashInspector.inspect(protectedMpd("urn:uuid:00000000-0000-0000-0000-000000000000"), manifestUrl)!!.drm)
            .isNotNull()
    }

    @Test
    fun `ContentProtection on a representation also counts`() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period><AdaptationSet mimeType="video/mp4">
                <Representation id="1" bandwidth="1" height="720">
                  <ContentProtection schemeIdUri="urn:uuid:EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED"/>
                </Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        assertThat(DashInspector.inspect(mpd, manifestUrl)!!.drm!!.system).isEqualTo("Widevine")
    }

    @Test
    fun `returns null for non MPD documents`() {
        assertThat(DashInspector.inspect("<html><body/></html>", manifestUrl)).isNull()
        assertThat(DashInspector.inspect("#EXTM3U", manifestUrl)).isNull()
        assertThat(DashInspector.inspect("<MPD><Period>", manifestUrl)).isNull()
        assertThat(DashInspector.inspect("", manifestUrl)).isNull()
    }

    @Test
    fun `accepts a BOM before the prolog`() {
        val info = DashInspector.inspect("﻿" + staticMpd, manifestUrl)
        assertThat(info).isNotNull()
    }

    @Test
    fun `XXE payload is rejected without reading files`() {
        val secret = temp.newFile("secret.txt").apply { writeText("TOP-SECRET-CONTENT") }
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE MPD [ <!ENTITY xxe SYSTEM "${secret.toURI()}"> ]>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <BaseURL>&xxe;</BaseURL>
              <Period><AdaptationSet contentType="text" lang="&xxe;">
                <Representation id="s" bandwidth="1"><BaseURL>&xxe;.vtt</BaseURL></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val info = DashInspector.inspect(xxe, manifestUrl)
        if (info != null) {
            assertThat(info.toString()).doesNotContain("TOP-SECRET-CONTENT")
        }
        assertThat(info).isNull()
    }

    @Test
    fun `billion laughs payload is rejected`() {
        val bomb = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <MPD type="static"><BaseURL>&lol3;</BaseURL></MPD>
        """.trimIndent()
        assertThat(DashInspector.inspect(bomb, manifestUrl)).isNull()
    }

    @Test
    fun `a manifest nested too deeply is refused instead of overflowing the stack`() {
        fun nested(levels: Int) = staticMpd.replace("</MPD>", "<a>".repeat(levels) + "</a>".repeat(levels) + "</MPD>")
        assertThat(DashInspector.inspect(nested(20), manifestUrl)).isNotNull()
        assertThat(DashInspector.inspect(nested(80), manifestUrl)).isNull()
        assertThat(DashInspector.inspect(nested(100_000), manifestUrl)).isNull()
    }

    @Test
    fun `parseIsoDuration handles common forms`() {
        assertThat(DashInspector.parseIsoDuration("PT1H2M3.5S")).isEqualTo(3723.5)
        assertThat(DashInspector.parseIsoDuration("P1DT2H")).isEqualTo(93_600.0)
        assertThat(DashInspector.parseIsoDuration("PT0S")).isEqualTo(0.0)
        assertThat(DashInspector.parseIsoDuration("PT634.566S")).isWithin(1e-9).of(634.566)
        assertThat(DashInspector.parseIsoDuration("P0Y0M0DT0H3M30.000S")).isEqualTo(210.0)
        assertThat(DashInspector.parseIsoDuration("pt1m")).isEqualTo(60.0)
        assertThat(DashInspector.parseIsoDuration(" PT1,5S ")).isEqualTo(1.5)
        assertThat(DashInspector.parseIsoDuration("P1W")).isEqualTo(604_800.0)
    }

    @Test
    fun `parseIsoDuration rejects invalid input`() {
        assertThat(DashInspector.parseIsoDuration(null)).isNull()
        assertThat(DashInspector.parseIsoDuration("")).isNull()
        assertThat(DashInspector.parseIsoDuration("P")).isNull()
        assertThat(DashInspector.parseIsoDuration("PT")).isNull()
        assertThat(DashInspector.parseIsoDuration("1H2M")).isNull()
        assertThat(DashInspector.parseIsoDuration("PT1X")).isNull()
        assertThat(DashInspector.parseIsoDuration("-PT5S")).isNull()
    }
}
