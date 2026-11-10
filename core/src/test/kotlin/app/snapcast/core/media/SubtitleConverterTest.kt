package app.snapcast.core.media

import app.snapcast.core.model.SubtitleFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleConverterTest {
    private val srt = """
        1
        00:00:01,000 --> 00:00:04,500
        Hello <b>world</b>!

        2
        00:00:05,250 --> 00:00:07,000 X1:100 X2:200 Y1:10 Y2:20
        {\an8}<font color="#ff0000">Red</font> text
        and a <i>second</i> line

        3
        00:01:02,003 --> 01:02:03,456
        <U>Under</U> &amp; Tom & Jerry
    """.trimIndent()

    @Test
    fun `converts SRT to WebVTT`() {
        val expected = """
            WEBVTT

            1
            00:00:01.000 --> 00:00:04.500
            Hello <b>world</b>!

            2
            00:00:05.250 --> 00:00:07.000
            Red text
            and a <i>second</i> line

            3
            00:01:02.003 --> 01:02:03.456
            <u>Under</u> &amp; Tom &amp; Jerry

        """.trimIndent()
        assertThat(SubtitleConverter.srtToVtt(srt)).isEqualTo(expected)
    }

    @Test
    fun `strips BOM and normalizes CRLF and CR line endings`() {
        val crlf = "﻿1\r\n00:00:01,000 --> 00:00:02,000\r\nLine one\r\nLine two\r\n\r\n2\r00:00:03,000 --> 00:00:04,000\rThree\r"
        val vtt = SubtitleConverter.srtToVtt(crlf)
        assertThat(vtt).isEqualTo(
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nLine one\nLine two\n\n2\n00:00:03.000 --> 00:00:04.000\nThree\n",
        )
        assertThat(vtt).doesNotContain("\r")
        assertThat(vtt).doesNotContain("﻿")
    }

    @Test
    fun `pads hour-less and short timestamps`() {
        val vtt = SubtitleConverter.srtToVtt("1\n01:02,5 --> 1:03:04.25\nShort\n")
        assertThat(vtt).contains("00:01:02.500 --> 01:03:04.250")
    }

    @Test
    fun `accepts timestamps without milliseconds`() {
        val vtt = SubtitleConverter.srtToVtt("7\n00:00:01 --> 00:00:02\nNo millis\n")
        assertThat(vtt).contains("7\n00:00:01.000 --> 00:00:02.000\nNo millis")
    }

    @Test
    fun `multi-line cues blank lines inside text and missing indices`() {
        val messy = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n\nstill first\n2\n00:00:03,000 --> 00:00:04,000\n42\n\n" +
            "00:00:05,000 --> 00:00:06,000\nno index\n"
        val vtt = SubtitleConverter.srtToVtt(messy)
        assertThat(vtt).isEqualTo(
            "WEBVTT\n\n" +
                "1\n00:00:01.000 --> 00:00:02.000\nFirst\nstill first\n\n" +
                "2\n00:00:03.000 --> 00:00:04.000\n42\n\n" +
                "00:00:05.000 --> 00:00:06.000\nno index\n",
        )
    }

    @Test
    fun `drops empty cues and escapes stray markup`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n\n2\n00:00:03,000 --> 00:00:04,000\n5 < 6 --> true <br>\n"
        val vtt = SubtitleConverter.srtToVtt(text)
        assertThat(vtt).isEqualTo("WEBVTT\n\n2\n00:00:03.000 --> 00:00:04.000\n5 &lt; 6 -> true\n")
    }

    @Test
    fun `empty input yields only the header`() {
        assertThat(SubtitleConverter.srtToVtt("")).isEqualTo("WEBVTT\n")
    }

    @Test
    fun `sniffs formats`() {
        assertThat(SubtitleConverter.sniffFormat("WEBVTT\n\n00:01.000 --> 00:02.000\nHi")).isEqualTo(SubtitleFormat.VTT)
        assertThat(SubtitleConverter.sniffFormat("﻿WEBVTT - title\n")).isEqualTo(SubtitleFormat.VTT)
        assertThat(SubtitleConverter.sniffFormat("WEBVTT")).isEqualTo(SubtitleFormat.VTT)
        assertThat(SubtitleConverter.sniffFormat(srt)).isEqualTo(SubtitleFormat.SRT)
        assertThat(SubtitleConverter.sniffFormat("\r\n1\r\n00:00:01,000 --> 00:00:02,000\r\nx")).isEqualTo(SubtitleFormat.SRT)
        assertThat(SubtitleConverter.sniffFormat("00:00:01,000 --> 00:00:02,000\nx")).isEqualTo(SubtitleFormat.SRT)
        assertThat(SubtitleConverter.sniffFormat("<?xml version=\"1.0\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\">"))
            .isEqualTo(SubtitleFormat.TTML)
        assertThat(SubtitleConverter.sniffFormat("WEBVTTX")).isEqualTo(SubtitleFormat.UNKNOWN)
        assertThat(SubtitleConverter.sniffFormat("hello world")).isEqualTo(SubtitleFormat.UNKNOWN)
        assertThat(SubtitleConverter.sniffFormat("")).isEqualTo(SubtitleFormat.UNKNOWN)
    }

    @Test
    fun `toVtt passes VTT through unchanged`() {
        val vtt = "WEBVTT\r\n\r\n00:01.000 --> 00:02.000 line:0\r\n<c.yellow>Hi</c>\r\n"
        assertThat(SubtitleConverter.toVtt(vtt)).isSameInstanceAs(vtt)
        assertThat(SubtitleConverter.toVtt(vtt, SubtitleFormat.VTT)).isSameInstanceAs(vtt)
    }

    @Test
    fun `toVtt adds a header to headerless VTT`() {
        assertThat(SubtitleConverter.toVtt("00:01.000 --> 00:02.000\nHi\n", SubtitleFormat.VTT))
            .isEqualTo("WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n")
    }

    @Test
    fun `toVtt converts SRT and unknown text`() {
        assertThat(SubtitleConverter.toVtt(srt)).isEqualTo(SubtitleConverter.srtToVtt(srt))
        assertThat(SubtitleConverter.toVtt(srt, SubtitleFormat.UNKNOWN)).isEqualTo(SubtitleConverter.srtToVtt(srt))
        assertThat(SubtitleConverter.toVtt("garbage", SubtitleFormat.UNKNOWN)).isEqualTo("WEBVTT\n")
    }

    @Test
    fun `toVtt converts TTML paragraphs`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:tickRate="10000000" xml:lang="en">
              <body><div>
                <p begin="00:00:01.500" end="00:00:03.000">Hello<br/>  world  </p>
                <p begin="40000000t" end="50000000t"><span>Tick &amp; tock</span></p>
                <p begin="6s" dur="1.5s">Offset</p>
                <p begin="00:00:00.000" end="00:00:00.500">   </p>
              </div></body>
            </tt>
        """.trimIndent()
        assertThat(SubtitleConverter.toVtt(ttml)).isEqualTo(
            "WEBVTT\n\n" +
                "00:00:01.500 --> 00:00:03.000\nHello\nworld\n\n" +
                "00:00:04.000 --> 00:00:05.000\nTick &amp; tock\n\n" +
                "00:00:06.000 --> 00:00:07.500\nOffset\n",
        )
    }

    @Test
    fun `TTML frame based clock times use the frame rate`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25">
            <body><div><p begin="00:00:01:05" end="00:00:02:00">Frames</p></div></body></tt>"""
        assertThat(SubtitleConverter.toVtt(ttml, SubtitleFormat.TTML)).contains("00:00:01.200 --> 00:00:02.000\nFrames")
    }

    @Test
    fun `invalid TTML yields an empty VTT document`() {
        assertThat(SubtitleConverter.toVtt("<tt><p>broken", SubtitleFormat.TTML)).isEqualTo("WEBVTT\n")
    }

    @Test
    fun `TTML nested too deeply yields an empty VTT document instead of overflowing the stack`() {
        fun nested(levels: Int) = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body>" + "<div>".repeat(levels) +
            "<p begin=\"1s\" end=\"2s\">Deep</p>" + "</div>".repeat(levels) + "</body></tt>"
        assertThat(SubtitleConverter.toVtt(nested(20), SubtitleFormat.TTML)).contains("Deep")
        assertThat(SubtitleConverter.toVtt(nested(80), SubtitleFormat.TTML)).isEqualTo("WEBVTT\n")
        assertThat(SubtitleConverter.toVtt(nested(100_000), SubtitleFormat.TTML)).isEqualTo("WEBVTT\n")
    }
}
