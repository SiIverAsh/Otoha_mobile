package io.github.otoha.mobile

import android.content.Context
import android.util.AtomicFile
import android.os.SystemClock
import io.github.otoha.core.PlaybackSnapshot
import io.github.otoha.core.WorkSummary
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class PersonalData(
    val version: Int = 1,
    val favorites: List<WorkSummary> = emptyList(),
    val recent: List<WorkSummary> = emptyList(),
    val snapshot: PlaybackSnapshot? = null,
    val showRestricted: Boolean = false,
    val asmrOneApiBaseUrl: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/** A small versioned, atomically replaced private store. Media bytes are never persisted. */
class AppStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "personal-data.json"))
    private val mutableData = MutableStateFlow(read())
    val data = mutableData.asStateFlow()
    private val mutableIncognito = MutableStateFlow(false)
    val incognito = mutableIncognito.asStateFlow()
    private val mutableSleepDeadline = MutableStateFlow(0L)
    val sleepDeadline = mutableSleepDeadline.asStateFlow()
    private val recording = RecordingPolicy()

    private fun read(): PersonalData = runCatching {
        file.openRead().bufferedReader().use { appJson.decodeFromString<PersonalData>(it.readText()) }
    }.getOrDefault(PersonalData())

    @Synchronized
    private fun update(transform: (PersonalData) -> PersonalData) {
        val updated = transform(mutableData.value)
        val stream = file.startWrite()
        try {
            stream.write(appJson.encodeToString(updated).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            mutableData.value = updated
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun toggleFavorite(work: WorkSummary) = update { data ->
        data.copy(favorites = if (data.favorites.any { it.ref.key == work.ref.key })
            data.favorites.filterNot { it.ref.key == work.ref.key } else listOf(work) + data.favorites)
    }

    fun setRestricted(value: Boolean) = update { it.copy(showRestricted = value) }
    fun setThemeMode(value: ThemeMode) = update { it.copy(themeMode = value) }
    fun setAsmrOneApiBaseUrl(raw: String) {
        val normalized = MirrorSettings.normalize(raw)
        update { it.copy(asmrOneApiBaseUrl = normalized) }
    }
    @Synchronized
    fun setIncognito(value: Boolean) {
        recording.setIncognito(value)
        mutableIncognito.value = value
    }
    @Synchronized
    fun beginPlayback(): String = recording.beginPlayback()
    @Synchronized
    fun continuePlayback(previousToken: String?): String? = recording.replacePlayback(previousToken)
    fun sleepAfter(minutes: Int) {
        require(minutes in listOf(0, 15, 30, 60))
        mutableSleepDeadline.value = if (minutes == 0) 0 else SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    @Synchronized
    fun savePlayback(snapshot: PlaybackSnapshot, queueToken: String?) {
        if (!recording.canRecord(queueToken)) return
        update { data -> data.copy(snapshot = snapshot,
            recent = (listOf(snapshot.work) + data.recent.filterNot { it.ref.key == snapshot.work.ref.key }).take(100)) }
    }

    @Synchronized
    fun clearHistory() {
        recording.revoke()
        update { it.copy(snapshot = null, recent = emptyList()) }
    }
}
