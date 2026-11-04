package com.sasch.cameragps.sharednew.database.logging

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LogRepositoryTest {
    @Test fun burstIsBoundedAndStoredInBatchesWithOccasionalPruningChecks() = runTest {
        val dao = Dao()
        val repository = LogRepository(dao, backgroundScope)
        repeat(1_000) { repository.insertLog(it.toLong(), 4, null, "message", null) }
        runCurrent()
        assertEquals(256, dao.entries.size)
        assertEquals(8, dao.batches)
        assertEquals(2, dao.countQueries)
    }

    @Test fun clearIsOrderedAfterBufferedWritesAndBeforeSubsequentWrites() = runTest {
        val dao = Dao()
        val repository = LogRepository(dao, backgroundScope)
        repeat(10) { repository.insertLog(it.toLong(), 4, null, "before", null) }
        val clear = async { repository.clearAllLogs() }
        runCurrent()
        clear.await()
        assertTrue(dao.entries.isEmpty())
        repository.insertLog(11, 4, null, "after", null)
        runCurrent()
        assertEquals(listOf("after"), dao.entries.map { it.message })
    }

    @Test fun oversizedExistingDatabaseIsPrunedOnFirstBatch() = runTest {
        val dao = Dao()
        repeat(1_100) { dao.entries += LogEntry(timestamp = it.toLong(), priority = 4, tag = null, message = "old", exception = null) }
        val repository = LogRepository(dao, backgroundScope)
        repository.insertLog(2_000, 4, null, "new", null)
        runCurrent()
        assertEquals(500, dao.entries.size)
        assertEquals("new", dao.entries.last().message)
    }

    @Test fun storageFailureDoesNotKillFutureLogging() = runTest {
        val dao = Dao()
        val repository = LogRepository(dao, backgroundScope)
        dao.failNextInsert = true
        repository.insertLog(1, 4, null, "failed", null)
        runCurrent()
        repository.insertLog(2, 4, null, "recovered", null)
        runCurrent()
        assertEquals(listOf("recovered"), dao.entries.map { it.message })
    }

    private class Dao : LogDao {
        val entries = mutableListOf<LogEntry>()
        var batches = 0
        var countQueries = 0
        var failNextInsert = false
        override suspend fun insertLog(logEntry: LogEntry) { entries += logEntry }
        override suspend fun insertLogs(logEntries: List<LogEntry>) {
            if (failNextInsert) { failNextInsert = false; error("Storage temporarily unavailable") }
            batches++
            entries += logEntries
        }
        override fun getAllLogs() = MutableStateFlow(entries.toList())
        override fun getRecentLogs(limit: Int) = MutableStateFlow(entries.takeLast(limit))
        override suspend fun deleteOldLogs(keepCount: Int) {
            val keep = entries.sortedBy { it.timestamp }.takeLast(keepCount)
            entries.clear()
            entries += keep
        }
        override suspend fun clearAllLogs() = entries.clear()
        override suspend fun getLogCount(): Int { countQueries++; return entries.size }
    }
}
