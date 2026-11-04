package com.sasch.cameragps.sharednew.database.logging

import androidx.room.RoomDatabase
import com.sasch.cameragps.sharednew.database.LogDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class LogRepository(private val logDao: LogDao, scope: CoroutineScope) {
    constructor(databaseBuilder: RoomDatabase.Builder<LogDatabase>) : this(
        LogDatabase.getRoomDatabase(databaseBuilder).logDao(),
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private sealed interface Command {
        data class Append(val entry: LogEntry) : Command
        data class Clear(val done: CompletableDeferred<Unit>) : Command
    }

    // Logging must never block BLE callbacks or accumulate unbounded coroutine jobs.
    // If diagnostics outpace storage, discard new logs until the writer catches up.
    private val commands = Channel<Command>(256)

    init {
        scope.launch {
            var insertsSincePrune = 100 // Check an existing database on the first batch.
            for (command in commands) {
                if (command is Command.Clear) {
                    clear(command)
                    continue
                }
                val batch = mutableListOf((command as Command.Append).entry)
                var clearAfterBatch: Command.Clear? = null
                while (batch.size < 32) {
                    when (val next = commands.tryReceive().getOrNull()) {
                        is Command.Append -> batch += next.entry
                        is Command.Clear -> { clearAfterBatch = next; break }
                        null -> break
                    }
                }
                try {
                    logDao.insertLogs(batch)
                    insertsSincePrune += batch.size
                    if (insertsSincePrune >= 100) {
                        insertsSincePrune = 0
                        if (logDao.getLogCount() > 1000) logDao.deleteOldLogs(500)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Best-effort diagnostics: a storage failure must not crash the
                    // app, kill the writer, or recursively log through this repository.
                }
                clearAfterBatch?.let { clear(it) }
            }
        }
    }

    private suspend fun clear(command: Command.Clear) {
        try {
            logDao.clearAllLogs()
            command.done.complete(Unit)
        } catch (cancelled: CancellationException) {
            command.done.cancel(cancelled)
            throw cancelled
        } catch (error: Exception) {
            command.done.completeExceptionally(error)
        }
    }

    fun insertLog(
        timestamp: Long,
        priority: Int,
        tag: String?,
        message: String,
        exception: String?
    ) {
        commands.trySend(Command.Append(LogEntry(
                timestamp = timestamp,
                priority = priority,
                tag = tag,
                message = message,
                exception = exception
            )))
    }

    fun getRecentLogs(limit: Int = 200): Flow<List<LogEntry>> = logDao.getRecentLogs(limit)

    suspend fun clearAllLogs() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Clear(done))
        done.await()
    }
}
