package app.snapcast.data.history

import android.database.SQLException
import android.util.Log
import app.snapcast.data.db.HistoryDao
import app.snapcast.data.db.toModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Room-backed [HistoryRepository]. Visits to internal pages (about:, data:, blob:, the built-in home page, …)
 * are not recorded; a missing title is stored as an empty string (the UI shows the URL instead).
 */
@Singleton
internal class RoomHistoryRepository @Inject constructor(
    private val dao: HistoryDao,
) : HistoryRepository {

    override fun observeHistory(query: String, limit: Int): Flow<List<HistoryEntry>> {
        val max = limit.coerceAtLeast(0)
        val text = query.trim()
        val rows = if (text.isEmpty()) {
            dao.observeRecent(max)
        } else {
            dao.observeMatching(HistoryQueries.containsPattern(text), max)
        }
        return rows.map { list -> list.map { it.toModel() } }.distinctUntilChanged()
    }

    override suspend fun recordVisit(url: String, title: String?) {
        if (!HistoryQueries.isRecordable(url)) return
        bestEffort { dao.recordVisit(url = url, title = HistoryQueries.cleanTitle(title), visitedAt = System.currentTimeMillis()) }
    }

    override suspend fun updateTitle(url: String, title: String) {
        val clean = HistoryQueries.cleanTitle(title) ?: return
        if (!HistoryQueries.isRecordable(url)) return
        bestEffort { dao.updateLatestTitle(url, clean) }
    }

    /** These writes happen by themselves while browsing: a full disk or a damaged database must not end the app. */
    private inline fun bestEffort(write: () -> Unit) {
        try {
            write()
        } catch (e: SQLException) {
            Log.w(TAG, "Could not write history", e)
        }
    }

    override suspend fun suggestions(text: String, limit: Int): List<HistoryEntry> {
        val needle = text.trim()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        return dao.suggestions(HistoryQueries.containsPattern(needle), limit).map { it.toModel() }
    }

    override suspend fun delete(id: Long) {
        dao.delete(id)
    }

    override suspend fun clear() {
        dao.clear()
    }

    private companion object {
        const val TAG = "SnapcastData"
    }
}
