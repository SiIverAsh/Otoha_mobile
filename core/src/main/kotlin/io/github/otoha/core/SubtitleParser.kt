package io.github.otoha.core

import org.jsoup.parser.Parser

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** Timing rules adapted from SiIverAsh/Otoha internal/subtitle/parse.go (GPL-3.0). */
object SubtitleParser {
    private val lrcTimestamp = Regex("\\[([0-9]{1,6}):([0-5][0-9])(?:[.:]([0-9]{1,3}))?\\]")
    private val lrcOffset = Regex("(?im)^\\s*\\[offset:\\s*([+-]?[0-9]+)\\s*\\]\\s*$")
    private const val clock = "(?:[0-9]{1,6}:)?[0-9]{2}:[0-5][0-9][,.][0-9]{1,3}"
    private val timingLine = Regex("^\\s*($clock)\\s*-->\\s*($clock)(?:\\s+.*)?$")
    private val markup = Regex("<[^>]*>")
    // Android's ICU regex engine rejects a bare closing brace, unlike desktop java.util.regex.
    private val assOverrides = Regex("\\{[^}]*\\}")

    fun parse(text: String, format: String): List<SubtitleCue> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = when (format.trim().removePrefix(".").lowercase()) {
            "lrc" -> parseLrc(normalized)
            "srt", "vtt" -> parseSrtVtt(normalized)
            "ass", "ssa" -> parseAss(normalized)
            else -> emptyList()
        }
        return cues.sortedBy { it.startMs }
    }

    /** End boundaries are exclusive; overlapping cues are retained for dual-language subtitles. */
    fun activeCues(cues: List<SubtitleCue>, positionMs: Long): List<SubtitleCue> =
        cues.filter { positionMs >= it.startMs && positionMs < it.endMs && it.text.isNotBlank() }

    private fun parseLrc(text: String): List<SubtitleCue> {
        val offset = lrcOffset.findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val starts = mutableListOf<Pair<Long, String>>()
        for (line in text.lineSequence()) {
            val stamps = lrcTimestamp.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val lyric = cleanMarkup(line.substring(stamps.last().range.last + 1)).trim()
            for (stamp in stamps) {
                val base = stamp.groupValues[1].toLong() * 60_000 + stamp.groupValues[2].toLong() * 1000 + fractionMs(stamp.groupValues[3])
                val start = saturatingAdd(base, offset).coerceAtLeast(0)
                starts += start to lyric
            }
        }
        val sorted = starts.sortedBy { it.first }
        val result = ArrayList<SubtitleCue>(sorted.size)
        var nextStart: Long? = null
        var groupStart: Long? = null
        for ((start, lyric) in sorted.asReversed()) {
            // Equal timestamps represent simultaneous lines, rather than zero-length cues.
            if (groupStart != null && start < groupStart) nextStart = groupStart
            groupStart = start
            result += SubtitleCue(start, nextStart ?: saturatingAdd(start, 5000L), lyric)
        }
        result.reverse()
        return result
    }

    private fun parseSrtVtt(text: String): List<SubtitleCue> {
        val lines = text.lines()
        val cues = mutableListOf<SubtitleCue>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line == "NOTE" || line.startsWith("NOTE ") || line == "STYLE" || line == "REGION") {
                do { index++ } while (index < lines.size && lines[index].isNotBlank())
                continue
            }
            val timing = timingLine.matchEntire(line)
            if (timing == null) { index++; continue }
            val start = parseClock(timing.groupValues[1])
            val end = parseClock(timing.groupValues[2])
            index++
            val body = mutableListOf<String>()
            while (index < lines.size && lines[index].isNotBlank()) {
                if (timingLine.matches(lines[index].trim())) break
                body += lines[index].trim()
                index++
            }
            val cleaned = cleanMarkup(body.joinToString("\n")).trim()
            if (start != null && end != null && end > start && cleaned.isNotBlank()) cues += SubtitleCue(start, end, cleaned)
        }
        return cues
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var inEvents = false
        var columns = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith('[') && line.endsWith(']')) {
                inEvents = line.equals("[Events]", true)
                continue
            }
            if (!inEvents) continue
            when {
                line.startsWith("Format:", true) -> columns = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                line.startsWith("Dialogue:", true) -> {
                    val textIndex = columns.indexOf("text")
                    val startIndex = columns.indexOf("start")
                    val endIndex = columns.indexOf("end")
                    if (textIndex < 0 || startIndex < 0 || endIndex < 0) continue
                    val fields = splitAssFields(line.substringAfter(':').trim(), columns.size, textIndex) ?: continue
                    val start = parseClock(fields[startIndex]) ?: continue
                    val end = parseClock(fields[endIndex]) ?: continue
                    val cleaned = assOverrides.replace(fields[textIndex], "")
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
                    if (end > start && cleaned.isNotBlank()) cues += SubtitleCue(start, end, cleaned)
                }
            }
        }
        return cues
    }

    // Preserve commas in ASS Text, including files with custom column ordering.
    private fun splitAssFields(payload: String, fieldCount: Int, textIndex: Int): List<String>? {
        val prefix = payload.split(',', limit = textIndex + 1)
        if (prefix.size <= textIndex) return null
        var tail = prefix.last()
        val suffix = mutableListOf<String>()
        repeat(fieldCount - textIndex - 1) {
            val separator = tail.lastIndexOf(',')
            if (separator < 0) return null
            suffix.add(0, tail.substring(separator + 1))
            tail = tail.substring(0, separator)
        }
        return prefix.dropLast(1) + tail + suffix
    }

    private fun parseClock(raw: String): Long? {
        val parts = raw.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull()?.takeIf { it in 0..999_999 } ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull()?.takeIf { it in 0..999_999 && (parts.size == 2 || it < 60) } ?: return null
        val seconds = parts.last().substringBefore('.').toLongOrNull()?.takeIf { it in 0..59 } ?: return null
        val fraction = parts.last().substringAfter('.', "")
        if (fraction.length > 3 || fraction.any { !it.isDigit() }) return null
        return hours * 3_600_000 + minutes * 60_000 + seconds * 1000 + fractionMs(fraction)
    }

    private fun fractionMs(fraction: String): Long = fraction.padEnd(3, '0').toLongOrNull() ?: 0L
    private fun cleanMarkup(text: String): String = Parser.unescapeEntities(
        markup.replace(text.replace(Regex("(?i)<br\\s*/?>"), "\n"), ""), false,
    )

    private fun saturatingAdd(left: Long, right: Long): Long = when {
        right > 0 && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        right < 0 && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
        else -> left + right
    }
}
