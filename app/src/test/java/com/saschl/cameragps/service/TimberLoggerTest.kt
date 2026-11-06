package com.saschl.cameragps.service

import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.logging
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class TimberLoggerTest {
    private data class Entry(
        val priority: Int,
        val tag: String?,
        val message: String,
        val error: Throwable?
    )

    private class RecordingTree(private val minimum: Int = 2) : Timber.Tree() {
        val entries = mutableListOf<Entry>()
        override fun isLoggable(tag: String?, priority: Int) = priority >= minimum
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += Entry(priority, tag, message, t)
        }
    }

    @Before
    fun setup() {
        Timber.uprootAll()
        KmLogging.setLoggers(TimberLogger())
    }

    @After
    fun cleanup() {
        KmLogging.setLoggers()
        Timber.uprootAll()
    }

    @Test
    fun sharedLogsReachTimberWithLevelsTagsAndExceptions() {
        val tree = RecordingTree()
        Timber.plant(tree)
        val failure = IllegalStateException("BLE failure")
        KmLogging.verbose("BLE", "verbose")
        KmLogging.debug("BLE", "debug")
        KmLogging.info("RemoteControlCoordinator", "Following CC09; progress 100% %s")
        KmLogging.warn("BLE", "warning", failure)
        KmLogging.error("BLE", "error", failure)

        assertEquals(listOf(2, 3, 4, 5, 6), tree.entries.map { it.priority })
        assertEquals("RemoteControlCoordinator", tree.entries[2].tag)
        assertEquals("Following CC09; progress 100% %s", tree.entries[2].message)
        assertSame(failure, tree.entries[3].error)
        assertSame(failure, tree.entries[4].error)
        assertTrue(tree.entries[4].message.contains("BLE failure"))
    }

    @Test
    fun replacingTreesAppliesNewThresholdWithoutReinstallingSharedLogger() {
        val warningsOnly = RecordingTree(5)
        Timber.plant(warningsOnly)
        KmLogging.info("BLE", "hidden")
        KmLogging.warn("BLE", "visible")
        assertEquals(listOf(5), warningsOnly.entries.map { it.priority })

        Timber.uprootAll()
        val infoEnabled = RecordingTree(4)
        Timber.plant(infoEnabled)
        KmLogging.info("BLE", "Following CC09")
        assertEquals(listOf("Following CC09"), infoEnabled.entries.map { it.message })
    }

    @Test
    fun disabledSharedLogsAreNotFormattedAndThresholdChangesTakeEffectImmediately() {
        var minimum = 5
        KmLogging.setLoggers(TimberLogger(PriorityLogLevel { minimum }))
        val tree = RecordingTree()
        Timber.plant(tree)
        val logger = logging("BLE")
        var formatted = 0
        logger.d { formatted++; "periodic debug" }
        logger.i { formatted++; "routine info" }
        assertEquals(0, formatted)
        assertTrue(tree.entries.isEmpty())
        logger.w { "registration failed" }
        assertEquals(listOf(5), tree.entries.map { it.priority })

        minimum = 3
        KmLogging.setupLoggingFlags()
        logger.d { formatted++; "requested diagnostics" }
        assertEquals(1, formatted)
        assertEquals(listOf(5, 3), tree.entries.map { it.priority })

        minimum = 5
        KmLogging.setupLoggingFlags()
        logger.d { formatted++; "hidden again" }
        assertEquals(1, formatted)
        assertEquals(listOf(5, 3), tree.entries.map { it.priority })
    }
}
