package app.snapcast.data.history

import android.database.sqlite.SQLiteFullException
import app.snapcast.data.db.HistoryDao
import app.snapcast.data.db.HistoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFailsWith

class RoomHistoryRepositoryTest {
    /** A database whose every read and write fails with [failure]. */
    private class FailingDao(private val failure: Throwable) : HistoryDao() {
        override fun observeRecent(limit: Int): Flow<List<HistoryEntity>> = emptyFlow()
        override fun observeMatching(pattern: String, limit: Int): Flow<List<HistoryEntity>> = emptyFlow()
        override suspend fun suggestions(pattern: String, limit: Int): List<HistoryEntity> = throw failure
        override suspend fun latest(): HistoryEntity? = throw failure
        override suspend fun insert(entry: HistoryEntity): Long = throw failure
        override suspend fun updateVisit(id: Long, title: String, visitedAt: Long) = throw failure
        override suspend fun updateLatestTitle(url: String, title: String) = throw failure
        override suspend fun delete(id: Long) = throw failure
        override suspend fun clear() = throw failure
    }

    @Test
    fun `a visit that cannot be stored is dropped instead of crashing the app`() = runTest {
        val repository = RoomHistoryRepository(FailingDao(SQLiteFullException()))
        repository.recordVisit("https://example.com/", "Example")
        repository.updateTitle("https://example.com/", "Example")
    }

    @Test
    fun `a cancelled write still reports its cancellation`() = runTest {
        val repository = RoomHistoryRepository(FailingDao(CancellationException("cancelled")))
        assertFailsWith<CancellationException> { repository.recordVisit("https://example.com/", "Example") }
    }
}
