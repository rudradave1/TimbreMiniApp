package com.rudra.timbreminiapp.core.trimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTrimmerTest {

    @Test
    fun `mp3 container maps to audio mpeg`() {
        val (ext, mime) = deriveExtensionAndMime("mp3", isVideo = false)
        assertEquals("mp3", ext)
        assertEquals("audio/mpeg", mime)
    }

    @Test
    fun `pure matroska container maps to mkv for video`() {
        val (ext, mime) = deriveExtensionAndMime("matroska", isVideo = true)
        assertEquals("mkv", ext)
        assertEquals("video/x-matroska", mime)
    }

    @Test
    fun `pure matroska container maps to mka for audio`() {
        val (ext, mime) = deriveExtensionAndMime("matroska", isVideo = false)
        assertEquals("mka", ext)
        assertEquals("audio/x-matroska", mime)
    }

    @Test
    fun `webm container maps to webm for video even when ffprobe reports matroska comma webm`() {
        val (ext, mime) = deriveExtensionAndMime("matroska,webm", isVideo = true)
        assertEquals("webm", ext)
        assertEquals("video/webm", mime)
    }

    @Test
    fun `webm audio maps to webm`() {
        val (ext, mime) = deriveExtensionAndMime("matroska,webm", isVideo = false)
        assertEquals("webm", ext)
        assertEquals("audio/webm", mime)
    }

    @Test
    fun `mp4 family audio maps to m4a`() {
        val (ext, mime) = deriveExtensionAndMime("mov,mp4,m4a,3gp,3g2,mj2", isVideo = false)
        assertEquals("m4a", ext)
        assertEquals("audio/mp4", mime)
    }

    @Test
    fun `unknown format falls back to mp4`() {
        val (ext, mime) = deriveExtensionAndMime("", isVideo = true)
        assertEquals("mp4", ext)
        assertEquals("video/mp4", mime)
    }

    @Test
    fun `mp4 command uses copy, fast seek and faststart`() {
        val cmd = buildTrimCommand("/in.mp4", "/out.mp4", 12.0, 5.0, "mp4")
        assertTrue(cmd.contains("-ss 12.0"))
        assertTrue(cmd.contains("-t 5.0"))
        assertTrue(cmd.contains("-c copy"))
        assertTrue(cmd.contains("-avoid_negative_ts make_zero"))
        assertTrue(cmd.contains("-movflags +faststart"))
        assertTrue(cmd.startsWith("-y "))
    }

    @Test
    fun `faststart only added for mp4 family`() {
        val mkv = buildTrimCommand("/in.mkv", "/out.mkv", 0.0, 3.0, "mkv")
        assertFalse(mkv.contains("-movflags +faststart"))

        val mp3 = buildTrimCommand("/in.mp3", "/out.mp3", 0.0, 3.0, "mp3")
        assertFalse(mp3.contains("-movflags +faststart"))
    }

    @Test
    fun `fractional seconds are preserved in the command`() {
        val cmd = buildTrimCommand("/in.mp4", "/out.mp4", 12.345, 8.25, "mp4")
        assertTrue(cmd.contains("-ss 12.345"))
        assertTrue(cmd.contains("-t 8.25"))
    }

    @Test
    fun `mime fallback maps audio mpeg to mp3`() {
        val (ext, mime) = deriveExtensionAndMimeFromMime("audio/mpeg", isVideo = false)
        assertEquals("mp3", ext)
        assertEquals("audio/mpeg", mime)
    }

    @Test
    fun `mime fallback maps matroska video to mkv`() {
        val (ext, mime) = deriveExtensionAndMimeFromMime("video/x-matroska", isVideo = true)
        assertEquals("mkv", ext)
        assertEquals("video/x-matroska", mime)
    }

    @Test
    fun `mime fallback maps mp4 audio to m4a`() {
        val (ext, mime) = deriveExtensionAndMimeFromMime("audio/mp4", isVideo = false)
        assertEquals("m4a", ext)
        assertEquals("audio/mp4", mime)
    }

    @Test
    fun `unknown mime falls back to video mp4`() {
        val (ext, mime) = deriveExtensionAndMimeFromMime("application/octet-stream", isVideo = true)
        assertEquals("mp4", ext)
        assertEquals("video/mp4", mime)
    }

    @Test
    fun `paths with spaces and quotes are escaped`() {
        val cmd = buildTrimCommand("/in folder/a'b.mp4", "/out.mp4", 1.0, 2.0, "mp4")
        assertTrue(cmd.contains("'/in folder/a'\\''b.mp4'"))
    }
}