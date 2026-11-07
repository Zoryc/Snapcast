package app.snapcast.proxy.server

import app.snapcast.proxy.SubtitleSource
import app.snapcast.core.model.SubtitleFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RegistryTest {
    private fun subtitle() = Registration.Subtitle(SubtitleSource.Inline("WEBVTT\n", SubtitleFormat.VTT), policy = null)

    @Test
    fun `when full, the least recently used registration goes, not one still being played`() {
        val registry = Registry()
        val playing = registry.add(subtitle())
        val others = List(Registry.MAX_ENTRIES - 1) { registry.add(subtitle()) }
        // The receiver keeps fetching the first one.
        assertThat(registry.subtitle(playing)).isNotNull()

        registry.add(subtitle())

        assertThat(registry.size).isEqualTo(Registry.MAX_ENTRIES)
        assertThat(registry.subtitle(playing)).isNotNull()
        assertThat(registry.subtitle(others.first())).isNull()
    }
}
