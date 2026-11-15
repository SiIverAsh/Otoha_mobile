package io.github.otoha.core

import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Adapted from SiIverAsh/Otoha internal/asmrone (GPL-3.0); no server is required. */
object AsmrOneParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val workNumberPattern = Regex("[RBV]J(?:[0-9]{8}|[0-9]{6})", RegexOption.IGNORE_CASE)
    private val subtitleExtensions = setOf("lrc", "srt", "vtt", "ass", "ssa")

    fun catalog(body: String, page: Int, pageSize: Int = 30): CatalogPage = parse {
        val root = json.parseToJsonElement(body).jsonObject
        val items = root["works"] as? JsonArray ?: throw SourceException("ASMR.one 目录响应缺少 works")
        val works = items.mapNotNull { item -> (item as? JsonObject)?.let { toWork(it) } }
        val pagination = root["pagination"] as? JsonObject
        val current = pagination?.number("currentPage")?.takeIf { it > 0 } ?: page
        val size = pagination?.number("pageSize")?.takeIf { it > 0 } ?: pageSize
        val total = pagination?.number("totalCount")
        CatalogPage(works, current, if (total != null) current.toLong() * size < total else items.size >= size)
    }

    fun work(body: String, fallbackId: String = ""): WorkSummary = parse {
        toWork(json.parseToJsonElement(body).jsonObject, fallbackId)
            ?: throw SourceException("ASMR.one 作品响应缺少标题或有效标识")
    }

    /** Public facet endpoints return an array; HTTP/shape failures must stay distinct from an empty list. */
    fun options(kind: FacetKind, body: String): List<FacetValue> = parse {
        val items = json.parseToJsonElement(body) as? JsonArray
            ?: throw SourceException("ASMR.one 筛选选项响应不是列表")
        val values = items.mapNotNull { (it as? JsonObject)?.let { item -> toFacet(item, kind) } }.distinctBy { it.key }
        if (items.isNotEmpty() && values.isEmpty()) throw SourceException("ASMR.one 筛选选项结构发生变化")
        values
    }

    fun tracks(body: String, ref: WorkRef): List<AudioTrack> = parse {
        val root = json.parseToJsonElement(body) as? JsonArray
            ?: throw SourceException("ASMR.one 曲目响应不是目录列表")
        val result = mutableListOf<AudioTrack>()
        fun walk(nodes: JsonArray, directory: List<String>, depth: Int) {
            if (depth > 64) throw SourceException("ASMR.one 目录嵌套过深")
            val objects = nodes.mapNotNull { it as? JsonObject }
            val subtitles = linkedMapOf<String, Subtitle>()
            for (node in objects) {
                if (node.string("type") == "folder") continue
                val title = node.string("title")
                val extension = title.substringAfterLast('.', "").lowercase()
                if (extension !in subtitleExtensions) continue
                val url = firstMediaUrl(node, "mediaDownloadUrl", "mediaStreamUrl")
                if (url.isNotBlank()) subtitles.putIfAbsent(title.substringBeforeLast('.').lowercase(), Subtitle(url, extension))
            }
            val occurrences = mutableMapOf<String, Int>()
            val hashOccurrences = mutableMapOf<String, Int>()
            for (node in objects) {
                val title = node.string("title").ifBlank { "未命名" }
                when (node.string("type")) {
                    "folder" -> walk(node["children"] as? JsonArray ?: JsonArray(emptyList()), directory + title, depth + 1)
                    "audio" -> {
                        val url = firstMediaUrl(node, "mediaStreamUrl", "mediaDownloadUrl", "streamLowQualityUrl")
                        if (url.isBlank()) continue
                        val occurrence = occurrences.getOrDefault(title, 0)
                        occurrences[title] = occurrence + 1
                        val hash = node.string("hash")
                        val stablePath = (directory + title).joinToString("\u0000") + "\u0000$occurrence"
                        val stableId = if (hash.isNotBlank()) {
                            val hashOccurrence = hashOccurrences.getOrDefault(hash, 0)
                            hashOccurrences[hash] = hashOccurrence + 1
                            // A repeated file in separate version folders remains a distinct queue entry.
                            "hash:$hash:scope:${digest(directory.joinToString("\u0000"))}:$hashOccurrence"
                        } else "path:${digest(stablePath)}"
                        val seconds = (node["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                        val durationMs = if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else 0L
                        val subtitle = subtitles[title.substringBeforeLast('.', title).lowercase()] ?: subtitles[title.lowercase()]
                        result += AudioTrack(
                            id = "${ref.key}:$stableId", title = title, url = url,
                            directory = directory, durationMs = durationMs,
                            isHls = url.substringBefore('?').endsWith(".m3u8", true), subtitle = subtitle,
                        )
                    }
                }
            }
        }
        walk(root, emptyList(), 0)
        result
    }

    fun canonicalWorkNumber(value: String): String = value.trim().takeIf(workNumberPattern::matches)?.uppercase().orEmpty()

    private fun toWork(item: JsonObject, fallbackId: String = ""): WorkSummary? {
        val numericId = item.number("id")?.takeIf { it > 0 }
        val sourceId = canonicalWorkNumber(item.string("source_id"))
        val fallback = canonicalWorkNumber(fallbackId)
        val identity = numericId?.toString() ?: sourceId.ifBlank { fallback }.ifBlank { fallbackId.takeIf { it.toIntOrNull()?.let { id -> id > 0 } == true }.orEmpty() }
        val title = item.string("title").trim()
        if (identity.isBlank() || title.isBlank()) return null
        val workNumber = sourceId.ifBlank { fallback }.ifBlank {
            numericId?.let { "RJ" + it.toString().padStart(if (it < 1_000_000) 6 else 8, '0') }.orEmpty()
        }
        val category = item.string("age_category_string").trim().lowercase()
        // Missing age data remains UNKNOWN; a false/missing nsfw flag alone is not proof of all-ages content.
        val rating = when {
            category in setOf("adult", "r18", "r15") -> ContentRating.RESTRICTED
            item["nsfw"].booleanValue() == true -> ContentRating.RESTRICTED
            category in setOf("general", "all_ages") -> ContentRating.GENERAL
            else -> ContentRating.UNKNOWN
        }
        val circleObject = item["circle"] as? JsonObject
        val actorObjects = (item["vas"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val tagObjects = (item["tags"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val circle = circleObject?.string("name").orEmpty().ifBlank { item.string("name") }
        val actors = actorObjects
            .map { it.string("name").trim() }.filter { it.isNotBlank() && !it.equals("N/A", true) }.distinct()
        val tags = tagObjects.map(::tagDisplayName).filter(String::isNotBlank).distinct()
        val facets = (listOfNotNull(circleObject?.let { toFacet(it, FacetKind.CIRCLE) }) +
            actorObjects.mapNotNull { toFacet(it, FacetKind.ACTOR) } +
            tagObjects.mapNotNull { toFacet(it, FacetKind.TAG) }).distinctBy { it.key }
        return WorkSummary(
            ref = WorkRef(SourceId.ASMR_ONE, identity, "https://asmr.one/work/$identity"),
            title = title, workNumber = workNumber, coverUrl = firstMediaUrl(item, "mainCoverUrl", "samCoverUrl", "thumbnailCoverUrl"),
            circle = circle, actors = actors, tags = tags, rating = rating, facets = facets,
        )
    }

    private fun toFacet(item: JsonObject, kind: FacetKind): FacetValue? {
        val id = item.string("id").trim()
        val original = item.string("name").trim()
        if (id.isBlank() || original.isBlank() || original.equals("N/A", true)) return null
        if (kind != FacetKind.ACTOR && id.toLongOrNull()?.let { it > 0 } != true) return null
        val display = if (kind == FacetKind.TAG) tagDisplayName(item) else original
        return FacetValue(SourceId.ASMR_ONE, kind, id, display, queryValue = original)
    }

    private fun tagDisplayName(tag: JsonObject): String =
        ((tag["i18n"] as? JsonObject)?.get("zh-cn") as? JsonObject)?.string("name").orEmpty().trim()
            .ifBlank { tag.string("name").trim() }

    private fun firstMediaUrl(node: JsonObject, vararg fields: String): String = fields.asSequence()
        .map { node.string(it).trim() }.map { if (it.startsWith("//")) "https:$it" else it }
        .firstOrNull { it.startsWith("https://", true) || it.startsWith("http://", true) }.orEmpty()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty().takeUnless { it == "null" }.orEmpty()
    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.booleanValue(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private inline fun <T> parse(block: () -> T): T = try { block() } catch (e: SerializationException) {
        throw SourceException("ASMR.one JSON 解析失败：${e.message}")
    } catch (e: IllegalArgumentException) {
        throw SourceException("ASMR.one 数据结构发生变化：${e.message}")
    }
}
