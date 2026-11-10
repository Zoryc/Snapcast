package app.snapcast.proxy.rewrite

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class DashRewriterTest {
    /** Readable stand-ins for the signed routes. */
    private val mapper = object : ProxyUrlMapper {
        override fun exact(absoluteUrl: String): String = "U($absoluteUrl)"

        override fun base(baseDir: String): String = "B($baseDir)/"
    }

    private val manifestUrl = "https://cdn.example/dash/stream.mpd"

    private fun rewrite(xml: String, url: String = manifestUrl): String =
        requireNotNull(DashRewriter.rewrite(xml, url, mapper)) { "manifest was refused" }

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun Document.elements(localName: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun mpd(body: String, root: String = "") =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" type=\"static\" $root>\n" +
            body + "\n</MPD>\n"

    @Test
    fun `without BaseURL templates resolve against the manifest url`() {
        val out = parse(
            rewrite(
                mpd(
                    """
                    <Period>
                      <AdaptationSet mimeType="video/mp4">
                        <SegmentTemplate media="${'$'}RepresentationID${'$'}/seg-${'$'}Number%05d${'$'}.m4s" initialization="${'$'}RepresentationID${'$'}/init.mp4" startNumber="1" timescale="1000" duration="4000"/>
                        <Representation id="720p" bandwidth="3000000"/>
                      </AdaptationSet>
                    </Period>
                    """.trimIndent(),
                ),
            ),
        )
        val template = out.elements("SegmentTemplate").single()
        assertThat(template.getAttribute("media")).isEqualTo("B(https://cdn.example/dash/)/\$RepresentationID\$/seg-\$Number%05d\$.m4s")
        assertThat(template.getAttribute("initialization")).isEqualTo("B(https://cdn.example/dash/)/\$RepresentationID\$/init.mp4")
        assertThat(template.getAttribute("startNumber")).isEqualTo("1")
        assertThat(out.elements("BaseURL")).isEmpty()
    }

    @Test
    fun `BaseURL chain across MPD, Period, AdaptationSet and Representation`() {
        val out = parse(
            rewrite(
                mpd(
                    """
                    <BaseURL>https://media.example/content/</BaseURL>
                    <Period id="p0">
                      <BaseURL>period1/</BaseURL>
                      <AdaptationSet mimeType="video/mp4">
                        <BaseURL>video/</BaseURL>
                        <Representation id="v1" bandwidth="1000">
                          <BaseURL>720/</BaseURL>
                          <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4"/>
                        </Representation>
                      </AdaptationSet>
                      <AdaptationSet mimeType="audio/mp4">
                        <Representation id="a1" bandwidth="128">
                          <BaseURL>audio/track.mp4</BaseURL>
                          <SegmentBase indexRange="0-999"><Initialization range="0-500"/></SegmentBase>
                        </Representation>
                      </AdaptationSet>
                    </Period>
                    """.trimIndent(),
                ),
            ),
        )
        val baseUrls = out.elements("BaseURL").map { it.textContent }
        assertThat(baseUrls).containsExactly(
            "B(https://media.example/content/)/",
            "B(https://media.example/content/period1/)/",
            "B(https://media.example/content/period1/video/)/",
            "B(https://media.example/content/period1/video/720/)/",
            "U(https://media.example/content/period1/audio/track.mp4)",
        ).inOrder()
        val template = out.elements("SegmentTemplate").single()
        assertThat(template.getAttribute("media")).isEqualTo("B(https://media.example/content/period1/video/720/)/seg-\$Number\$.m4s")
        assertThat(template.getAttribute("initialization")).isEqualTo("U(https://media.example/content/period1/video/720/init.mp4)")
        // Byte ranges of SegmentBase are untouched.
        assertThat(out.elements("Initialization").single().getAttribute("range")).isEqualTo("0-500")
        assertThat(out.elements("Initialization").single().hasAttribute("sourceURL")).isFalse()
    }

    @Test
    fun `SegmentTemplate with absolute urls, dot segments and queries`() {
        val out = parse(
            rewrite(
                mpd(
                    """
                    <Period>
                      <AdaptationSet>
                        <Representation id="v" bandwidth="1">
                          <SegmentTemplate media="../chunks/${'$'}RepresentationID${'$'}_${'$'}Time${'$'}.m4s?token=a+b&amp;x=${'$'}Number${'$'}" initialization="https://init.example/i/${'$'}Bandwidth${'$'}.mp4" index="sidx+1.idx"/>
                        </Representation>
                      </AdaptationSet>
                    </Period>
                    """.trimIndent(),
                ),
                url = "https://cdn.example/dash/sub/stream.mpd",
            ),
        )
        val template = out.elements("SegmentTemplate").single()
        assertThat(template.getAttribute("media"))
            .isEqualTo("B(https://cdn.example/dash/chunks/)/\$RepresentationID\$_\$Time\$.m4s?token=a+b&x=\$Number\$")
        assertThat(template.getAttribute("initialization")).isEqualTo("B(https://init.example/i/)/\$Bandwidth\$.mp4")
        // Without placeholders the exact route is used.
        assertThat(template.getAttribute("index")).isEqualTo("U(https://cdn.example/dash/sub/sidx+1.idx)")
    }

    @Test
    fun `plus signs in a templated path are protected from NanoHTTPD decoding`() {
        val out = parse(
            rewrite(
                mpd(
                    """
                    <Period><AdaptationSet><Representation id="v" bandwidth="1">
                      <SegmentTemplate media="a+b/seg+${'$'}Number${'$'}.m4s?q=x+y"/>
                    </Representation></AdaptationSet></Period>
                    """.trimIndent(),
                ),
            ),
        )
        assertThat(out.elements("SegmentTemplate").single().getAttribute("media"))
            .isEqualTo("B(https://cdn.example/dash/a+b/)/seg%2B\$Number\$.m4s?q=x+y")
    }

    @Test
    fun `SegmentList urls, initialization and index`() {
        val out = parse(
            rewrite(
                mpd(
                    """
                    <Period>
                      <BaseURL>https://list.example/v/</BaseURL>
                      <AdaptationSet>
                        <Representation id="v" bandwidth="1">
                          <SegmentList duration="10">
                            <Initialization sourceURL="init.mp4"/>
                            <RepresentationIndex sourceURL="index.sidx"/>
                            <SegmentURL media="s1.m4s"/>
                            <SegmentURL media="/abs/s2.m4s" mediaRange="0-99" index="s2.idx"/>
                          </SegmentList>
                        </Representation>
                      </AdaptationSet>
                    </Period>
                    """.trimIndent(),
                ),
            ),
        )
        assertThat(out.elements("Initialization").single().getAttribute("sourceURL")).isEqualTo("U(https://list.example/v/init.mp4)")
        assertThat(out.elements("RepresentationIndex").single().getAttribute("sourceURL")).isEqualTo("U(https://list.example/v/index.sidx)")
        val segments = out.elements("SegmentURL")
        assertThat(segments.map { it.getAttribute("media") })
            .containsExactly("U(https://list.example/v/s1.m4s)", "U(https://list.example/abs/s2.m4s)").inOrder()
        assertThat(segments[1].getAttribute("index")).isEqualTo("U(https://list.example/v/s2.idx)")
        assertThat(segments[1].getAttribute("mediaRange")).isEqualTo("0-99")
    }

    @Test
    fun `Location, PatchLocation and xlink hrefs become exact routes`() {
        val out = parse(
            rewrite(
                mpd(
                    """
                    <Location>live/refresh.mpd?t=1</Location>
                    <PatchLocation ttl="60">https://patch.example/p.mpp</PatchLocation>
                    <Period xlink:href="periods/p1.xml" xlink:actuate="onLoad"/>
                    <Period xlink:href="urn:mpeg:dash:resolve-to-zero:2013"/>
                    """.trimIndent(),
                    root = "minimumUpdatePeriod=\"PT2S\"",
                ),
            ),
        )
        assertThat(out.elements("Location").single().textContent).isEqualTo("U(https://cdn.example/dash/live/refresh.mpd?t=1)")
        assertThat(out.elements("PatchLocation").single().textContent).isEqualTo("U(https://patch.example/p.mpp)")
        val periods = out.elements("Period")
        assertThat(periods[0].getAttributeNS("http://www.w3.org/1999/xlink", "href")).isEqualTo("U(https://cdn.example/dash/periods/p1.xml)")
        assertThat(periods[1].getAttributeNS("http://www.w3.org/1999/xlink", "href")).isEqualTo("urn:mpeg:dash:resolve-to-zero:2013")
    }

    @Test
    fun `inherited template is copied onto representations that have their own base`() {
        val out = parse(
            rewrite(
                mpd(
                    """
                    <Period>
                      <AdaptationSet>
                        <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s" initialization="init.mp4" timescale="1000" duration="2000">
                          <SegmentTimeline><S t="0" d="2000" r="9"/></SegmentTimeline>
                        </SegmentTemplate>
                        <Representation id="low" bandwidth="1"><BaseURL>low/</BaseURL></Representation>
                        <Representation id="same" bandwidth="2"/>
                      </AdaptationSet>
                    </Period>
                    """.trimIndent(),
                ),
            ),
        )
        val (setTemplate, lowTemplate) = out.elements("SegmentTemplate")
        assertThat(setTemplate.getAttribute("media")).isEqualTo("B(https://cdn.example/dash/)/seg-\$Number\$.m4s")
        assertThat(setTemplate.getAttribute("initialization")).isEqualTo("U(https://cdn.example/dash/init.mp4)")
        assertThat((lowTemplate.parentNode as Element).getAttribute("id")).isEqualTo("low")
        assertThat(lowTemplate.getAttribute("media")).isEqualTo("B(https://cdn.example/dash/low/)/seg-\$Number\$.m4s")
        assertThat(lowTemplate.getAttribute("initialization")).isEqualTo("U(https://cdn.example/dash/low/init.mp4)")
        // Timing stays inherited from the AdaptationSet template.
        assertThat(lowTemplate.hasAttribute("timescale")).isFalse()
        assertThat(lowTemplate.namespaceURI).isEqualTo("urn:mpeg:dash:schema:mpd:2011")
        assertThat(out.elements("SegmentTemplate")).hasSize(2)
    }

    @Test
    fun `serializes with an xml declaration and keeps namespaces, prefixes and text`() {
        val xml = mpd(
            """
            <!-- packaged by test -->
            <Period>
              <AdaptationSet>
                <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" cenc:default_KID="1234" xmlns:cenc="urn:mpeg:cenc:2013"/>
                <Label>Caf&#233; &amp; more &lt;3</Label>
                <Representation id="v" bandwidth="1"><BaseURL>v.mp4</BaseURL></Representation>
              </AdaptationSet>
            </Period>
            """.trimIndent(),
        )
        val text = rewrite(xml)
        assertThat(text).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        assertThat(text).contains("<!-- packaged by test -->")
        val out = parse(text)
        val root = out.documentElement
        assertThat(root.namespaceURI).isEqualTo("urn:mpeg:dash:schema:mpd:2011")
        val protection = out.elements("ContentProtection").single()
        assertThat(protection.getAttributeNS("urn:mpeg:cenc:2013", "default_KID")).isEqualTo("1234")
        assertThat(out.elements("Label").single().textContent).isEqualTo("Café & more <3")
        assertThat(out.elements("BaseURL").single().textContent).isEqualTo("U(https://cdn.example/dash/v.mp4)")
    }

    @Test
    fun `typical ffmpeg live-profile manifest keeps its structure`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xmlns="urn:mpeg:dash:schema:mpd:2011"
                xsi:schemaLocation="urn:mpeg:dash:schema:mpd:2011 http://standards.iso.org/ittf/PubliclyAvailableStandards/MPEG-DASH_schema_files/DASH-MPD.xsd"
                profiles="urn:mpeg:dash:profile:isoff-live:2011" type="static" mediaPresentationDuration="PT10M34.6S" minBufferTime="PT2S">
              <ProgramInformation><Title>Sample</Title></ProgramInformation>
              <Period id="0" start="PT0S">
                <AdaptationSet id="0" contentType="video" segmentAlignment="true" bitstreamSwitching="true" lang="und">
                  <Representation id="0" mimeType="video/mp4" codecs="avc1.640028" bandwidth="4000000" width="1920" height="1080">
                    <SegmentTemplate timescale="12288" initialization="init-stream${'$'}RepresentationID${'$'}.m4s" media="chunk-stream${'$'}RepresentationID${'$'}-${'$'}Number%05d${'$'}.m4s" startNumber="1">
                      <SegmentTimeline>
                        <S t="0" d="49152" r="160"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet id="1" contentType="audio" segmentAlignment="true" lang="eng">
                  <Representation id="1" mimeType="audio/mp4" codecs="mp4a.40.2" bandwidth="128000" audioSamplingRate="48000">
                    <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>
                    <SegmentTemplate timescale="48000" initialization="init-stream${'$'}RepresentationID${'$'}.m4s" media="chunk-stream${'$'}RepresentationID${'$'}-${'$'}Number%05d${'$'}.m4s" startNumber="1">
                      <SegmentTimeline><S t="0" d="96256"/><S d="95232" r="2"/></SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val text = rewrite(xml, "https://vod.example/bbb/manifest.mpd")
        val out = parse(text)

        val root = out.documentElement
        assertThat(root.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation")).startsWith("urn:mpeg:dash:schema:mpd:2011 ")
        assertThat(root.getAttribute("mediaPresentationDuration")).isEqualTo("PT10M34.6S")
        assertThat(out.elements("Title").single().textContent).isEqualTo("Sample")
        assertThat(out.elements("S")).hasSize(3)
        out.elements("SegmentTemplate").forEach { template ->
            assertThat(template.getAttribute("media")).isEqualTo("B(https://vod.example/bbb/)/chunk-stream\$RepresentationID\$-\$Number%05d\$.m4s")
            assertThat(template.getAttribute("initialization")).isEqualTo("B(https://vod.example/bbb/)/init-stream\$RepresentationID\$.m4s")
        }
        assertThat(out.elements("AdaptationSet")[0].getAttribute("bitstreamSwitching")).isEqualTo("true")
        assertThat(out.elements("AudioChannelConfiguration").single().getAttribute("value")).isEqualTo("2")
    }

    @Test
    fun `refuses documents with a DTD, other roots and malformed xml`() {
        val dtd = "<?xml version=\"1.0\"?><!DOCTYPE MPD [<!ENTITY x \"boom\">]><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">&x;</MPD>"
        assertThat(DashRewriter.rewrite(dtd, manifestUrl, mapper)).isNull()
        assertThat(DashRewriter.rewrite("<html><body/></html>", manifestUrl, mapper)).isNull()
        assertThat(DashRewriter.rewrite("<MPD><Period>", manifestUrl, mapper)).isNull()
        assertThat(DashRewriter.rewrite("", manifestUrl, mapper)).isNull()
    }

    @Test
    fun `a manifest nested too deeply is refused instead of overflowing the stack`() {
        fun nested(levels: Int) = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">" + "<a>".repeat(levels) + "</a>".repeat(levels) + "</MPD>"
        assertThat(DashRewriter.rewrite(nested(20), manifestUrl, mapper)).isNotNull()
        assertThat(DashRewriter.rewrite(nested(80), manifestUrl, mapper)).isNull()
        assertThat(DashRewriter.rewrite(nested(100_000), manifestUrl, mapper)).isNull()
    }

    @Test
    fun `xml decoding honours the declared encoding`() {
        val bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><MPD><Label>Café</Label></MPD>".toByteArray(Charsets.ISO_8859_1)
        assertThat(SafeXml.decode(bytes, null)).contains("Café")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<MPD/>".toByteArray()
        assertThat(SafeXml.decode(bom, Charsets.ISO_8859_1)).isEqualTo("<MPD/>")
    }
}
