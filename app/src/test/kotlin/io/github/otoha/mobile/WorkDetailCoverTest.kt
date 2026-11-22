package io.github.otoha.mobile

import io.github.otoha.core.AudioTrack
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WorkDetailCoverTest {
    private val ref = WorkRef(SourceId.ASMR_ONE, "123456")
    private val selected = WorkSummary(ref, "列表标题", coverUrl = "https://example.org/list.jpg")
    private val detail = WorkDetail(
        summary = WorkSummary(ref.copy(pageUrl = "https://asmr.one/work/123456"), "详情标题"),
        tracks = listOf(AudioTrack("track", "音轨", "https://example.org/audio.mp3")),
    )

    @Test
    fun missingDetailCoverUsesSelectedWorkCover() {
        val result = detail.withKnownCover(selected)

        assertEquals(detail.copy(summary = detail.summary.copy(coverUrl = selected.coverUrl)), result)
    }

    @Test
    fun freshDetailCoverTakesPriority() {
        val fresh = detail.copy(summary = detail.summary.copy(coverUrl = "https://example.org/detail.jpg"))

        assertSame(fresh, fresh.withKnownCover(selected))
    }

    @Test
    fun differentWorkOrSourceDoesNotSupplyCover() {
        val differentWork = selected.copy(ref = ref.copy(id = "654321"))
        val differentSource = selected.copy(ref = ref.copy(source = SourceId.ASMR18))

        assertSame(detail, detail.withKnownCover(differentWork))
        assertSame(detail, detail.withKnownCover(differentSource))
    }

    @Test
    fun absentOrBlankCoverKeepsDetailUnchanged() {
        assertSame(detail, detail.withKnownCover(null))
        assertSame(detail, detail.withKnownCover(selected.copy(coverUrl = " ")))
    }
}
