package io.github.otoha.core

import java.text.Normalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** A title and duration published in a DLsite work's track list. */
internal data class DlsiteTrack(val title: String, val durationMs: Long)

/** Reads complete track lists following the desktop Otoha DLsite parser. */
internal object DlsiteTrackListParser {
    private const val MIN_TRACK_COUNT = 2
    private const val SECONDS_PER_MINUTE = 60
    private const val MILLIS_PER_SECOND = 1_000L
    private const val TRACK_LIST_SELECTOR = "ul.c-productContent__trackList"
    private const val TRACK_ITEM_CLASS = "c-productContent__trackListItem"
    private const val TRACK_TITLE_SELECTOR = ".c-productContent__trackItemTitle"
    private const val TRACK_TIME_SELECTOR = ".c-productContent__trackItemTime"
    private const val TRACK_DESCRIPTION_SELECTOR = ".c-productContent__text"
    private const val TIME_FORMAT = "[0-9０-９]{1,4}[:：](?:[0-5０-５][0-9０-９][:：])?[0-5０-５][0-9０-９]"
    private const val TRACK_NUMBER = "[0-9０-９]{1,3}"
    private const val TRACK_PREFIX = "(?:トラック|(?i:track))[ \\t　]*"

    private val durationPattern = Regex("$TIME_FORMAT")
    private val endingTime = Regex("(?:[(（](?:SE(?:あり|なし)[/／])?$TIME_FORMAT[)）]|$TIME_FORMAT)$")
    private val approximate = Regex("(?i)(?:約|およそ|\\babout|\\bapprox(?:\\.|imately)?)$")
    private val numberedRows = listOf(
        Regex("^($TRACK_NUMBER)[ .．、_　\\t]*([^0-9０-９].*|)$"),
        Regex("^【[ \\t　]*$TRACK_PREFIX($TRACK_NUMBER)[ \\t　]*】(.*)$"),
        Regex("^(?:★[ \\t　]*)?$TRACK_PREFIX($TRACK_NUMBER)[ .．、_　\\t:：-]*([^0-9０-９].*|)$"),
    )
    private val totalTitles = setOf("総収録時間", "合計収録時間", "合計時間", "総再生時間", "合計")

    /** Returns the one complete timed list, or an empty list for ambiguous pages. */
    fun parse(html: String): List<DlsiteTrack> {
        val document = Jsoup.parse(html)
        val tables = document.select(TRACK_LIST_SELECTOR).map(::parseTable).filter { it.size >= MIN_TRACK_COUNT }
        if (tables.isNotEmpty()) return tables.singleOrNull().orEmpty()

        val descriptions = document.select(TRACK_DESCRIPTION_SELECTOR)
            .map(::parseNumberedList)
            .filter { it.size >= MIN_TRACK_COUNT }
        return descriptions.singleOrNull().orEmpty()
    }

    private fun parseTable(table: Element): List<DlsiteTrack> {
        val rows = table.children().filter { it.normalName() == "li" && it.hasClass(TRACK_ITEM_CLASS) }
        return rows.mapIndexedNotNull { index, row ->
            val title = row.select(TRACK_TITLE_SELECTOR).singleOrNull()?.text()?.trim().orEmpty()
            if (index == rows.lastIndex && title.trimEnd(':', '：') in totalTitles) return@mapIndexedNotNull null
            if (title.isEmpty()) return emptyList()
            val timing = row.select(TRACK_TIME_SELECTOR).singleOrNull()?.text()?.trim() ?: return emptyList()
            val durationMs = parseDuration(timing) ?: return emptyList()
            DlsiteTrack(title, durationMs)
        }
    }

    private fun parseNumberedList(description: Element): List<DlsiteTrack> {
        val entries = mutableListOf<DlsiteTrack>()
        var firstNumber = 0
        for (rawLine in description.wholeText().lineSequence()) {
            val line = rawLine.trim()
            val match = numberedRows.firstNotNullOfOrNull { it.matchEntire(line) } ?: continue
            val (ordinal, label) = match.destructured
            val number = Normalizer.normalize(ordinal, Normalizer.Form.NFKC).toInt()
            if (entries.isEmpty()) {
                if (number > 1) return emptyList()
                firstNumber = number
            }
            if (number != firstNumber + entries.size) return emptyList()
            entries += parseNumberedTrack(label) ?: return emptyList()
        }
        return entries
    }

    private fun parseNumberedTrack(label: String): DlsiteTrack? {
        val timing = endingTime.find(label) ?: return null
        val title = label.substring(0, timing.range.first).trim().trimEnd('・', '…').trimEnd()
        if (title.isEmpty() || title.last() in "~～〜-–－:：") return null
        if (approximate.containsMatchIn(title) || durationPattern.containsMatchIn(title)) return null

        val duration = timing.value.trim('(', ')', '（', '）').substringAfterLast('/').substringAfterLast('／')
        val durationMs = parseDuration(duration) ?: return null
        return DlsiteTrack(title, durationMs)
    }

    private fun parseDuration(label: String): Long? {
        if (!durationPattern.matches(label)) return null
        val parts = Normalizer.normalize(label, Normalizer.Form.NFKC).split(':')
        val seconds = parts.fold(0L) { total, part -> total * SECONDS_PER_MINUTE + part.toLong() }
        return (seconds * MILLIS_PER_SECOND).takeIf { it > 0L }
    }
}
