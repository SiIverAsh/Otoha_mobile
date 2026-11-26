package io.github.otoha.core

import org.junit.Assert.assertEquals
import org.junit.Test

class Asmr18ChapterFallbackTest {
    private val track = AudioTrack(
        id = "file2_content-1",
        title = "SEなし",
        url = "https://cdn3.cloudintech.net/file/RJ01175528/second.m3u8",
        directory = listOf("file2_content-1"),
        isHls = true,
    )
    private val entries = listOf(DlsiteTrack("Intro", 4_000), DlsiteTrack("Main", 6_000))

    @Test fun splitsTheSelectedVersionAndUsesTheMeasuredEndForTheLastChapter() {
        val entries = listOf(774L, 776L, 850L, 900L, 900L, 962L).mapIndexed { index, seconds ->
            DlsiteTrack("Chapter ${index + 1}", seconds * 1_000)
        }
        val playlist = mediaPlaylist(*Array(516) { "10.000" }, "4.440")

        val chapters = splitAsmr18FromPublishedTracks(track, entries, playlist)

        assertEquals(entries.map { it.title }, chapters.map { it.title })
        val starts = listOf(0L, 774_000L, 1_550_000L, 2_400_000L, 3_300_000L, 4_200_000L)
        assertEquals(starts, chapters.map { it.clipStartMs })
        assertEquals(starts.map { "${track.id}:chapter:$it" }, chapters.map { it.id })
        assertEquals(
            listOf(774_000L, 776_000L, 850_000L, 900_000L, 900_000L, 964_440L),
            chapters.map { it.durationMs },
        )
        assertEquals(setOf(track.url), chapters.map { it.url }.toSet())
        assertEquals(setOf(track.directory), chapters.map { it.directory }.toSet())
        assertEquals(setOf(track.id), chapters.map { it.sourceTrackId }.toSet())
        assertEquals(setOf(track.title), chapters.map { it.sourceTrackTitle }.toSet())
    }

    @Test fun acceptsEndOfListTagBeforeTheFinalSegments() {
        val playlist = mediaPlaylist("4.125", "5.875")
            .replace("#EXT-X-ENDLIST\n", "")
            .replace("#EXTINF:5.875,", "#EXT-X-ENDLIST\n#EXTINF:5.875,")

        val chapters = splitAsmr18FromPublishedTracks(track, entries, playlist)

        assertEquals(listOf(4_000L, 6_000L), chapters.map { it.durationMs })
    }

    @Test fun requiresAgreementWithinTheToleranceAndRoomForTheLastChapter() {
        val shorterLastTrack = listOf(entries.first(), DlsiteTrack("Main", 1_000))
        val longList = List(12) { DlsiteTrack("Chapter ${it + 1}", 1_000) }

        assertEquals(2, splitAsmr18FromPublishedTracks(track, entries, mediaPlaylist("12.500")).size)
        assertEquals(listOf(track), splitAsmr18FromPublishedTracks(track, entries, mediaPlaylist("12.501")))
        assertEquals(listOf(track), splitAsmr18FromPublishedTracks(track, longList, mediaPlaylist("22.001")))
        assertEquals(listOf(track), splitAsmr18FromPublishedTracks(track, shorterLastTrack, mediaPlaylist("4.000")))
    }

    @Test fun rejectsManifestsWithoutOneCompleteFiniteMediaTimeline() {
        val validPlaylist = mediaPlaylist("10")
        val invalidPlaylists = mapOf(
            "missing header" to validPlaylist.substringAfter('\n'),
            "live window" to validPlaylist.substringBefore("#EXT-X-ENDLIST"),
            "empty" to mediaPlaylist(),
            "master playlist" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=320000\naudio.m3u8\n#EXT-X-ENDLIST",
            "mixed media and master tags" to validPlaylist.replace(
                "#EXTINF", "#EXT-X-SESSION-DATA:DATA-ID=\"info.example\",VALUE=\"info\"\n#EXTINF",
            ),
            "duplicate end tag" to "$validPlaylist#EXT-X-ENDLIST",
            "missing segment" to validPlaylist.replace("segment0.ts\n", ""),
            "missing duration" to validPlaylist.replace("#EXTINF:10,\n", ""),
            "zero-length segment" to mediaPlaylist("0", "10"),
            "invalid length" to mediaPlaylist("NaN", "10"),
            "length outside the player range" to mediaPlaylist("9223372036855"),
            "delta update" to validPlaylist.replace("#EXTINF", "#EXT-X-SKIP:SKIPPED-SEGMENTS=3\n#EXTINF"),
            "partial segment" to validPlaylist.replace(
                "#EXT-X-ENDLIST", "#EXT-X-PART:DURATION=1,URI=\"tail.ts\"\n#EXT-X-ENDLIST",
            ),
        )

        for ((scenario, playlist) in invalidPlaylists) {
            assertEquals(scenario, listOf(track), splitAsmr18FromPublishedTracks(track, entries, playlist))
        }
    }

    @Test fun rejectsIncompletePublishedTracksAndDoesNotSplitAClipAgain() {
        val playlist = mediaPlaylist("10")
        val trackWithoutHls = track.copy(isHls = false)
        val existingClip = track.copy(clipStartMs = 0L, sourceTrackId = "original")

        assertEquals(listOf(track), splitAsmr18FromPublishedTracks(track, entries.take(1), playlist))
        assertEquals(listOf(track), splitAsmr18FromPublishedTracks(track, entries + DlsiteTrack("", 500), playlist))
        assertEquals(listOf(track), splitAsmr18FromPublishedTracks(track, entries + DlsiteTrack("Zero", 0), playlist))
        assertEquals(
            listOf(track),
            splitAsmr18FromPublishedTracks(track, entries + DlsiteTrack("Overflow", Long.MAX_VALUE), playlist),
        )
        assertEquals(listOf(trackWithoutHls), splitAsmr18FromPublishedTracks(trackWithoutHls, entries, playlist))
        assertEquals(listOf(existingClip), splitAsmr18FromPublishedTracks(existingClip, entries, playlist))
    }

    private fun mediaPlaylist(vararg segmentSeconds: String): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:23")
        segmentSeconds.forEachIndexed { index, seconds ->
            appendLine("#EXTINF:$seconds,")
            appendLine("segment$index.ts")
        }
        appendLine("#EXT-X-ENDLIST")
    }
}
