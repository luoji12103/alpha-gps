package com.sasch.cameragps.sharednew.bluetooth.location

import com.diamondedge.logging.logging
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleGattPort
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationDataConfig
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationPacketBuilder
import com.sasch.cameragps.sharednew.bluetooth.coordinator.PlatformTimeZoneInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

sealed interface LocationEvent {
    /** First fix of the session was accepted. */
    data object FirstFixAcquired : LocationEvent

    /** A periodic tick fired with no fix available. */
    data object NoLocationAvailable : LocationEvent
}

/**
 * Shared location transmission: freshness/accuracy gating, periodic resend and
 * send-to-ready-sessions. Unifies the former Android
 * `LocationTransmissionCoordinator` and iOS `IosLocationTransmissionManager`.
 *
 * All packet sends go through [port] (the queue-backed [BleGattPort]) so they
 * are serialized with every other BLE operation.
 */
class LocationTransmissionManager(
    private val source: LocationSource,
    private val readySessions: () -> Set<String>,
    private val configFor: (String) -> LocationDataConfig?,
    private val port: BleGattPort,
    private val scope: CoroutineScope,
    /** iOS: app-level transmission toggle. Android: always allowed. */
    private val isTransmissionAllowed: () -> Boolean = { true },
    private val policy: LocationTransmissionPolicy = LocationTransmissionPolicy(),
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val log = logging()

    private val _events = Channel<LocationEvent>(Channel.BUFFERED)
    val events: Flow<LocationEvent> = _events.receiveAsFlow()

    private val _isActive = MutableStateFlow(false)

    /** `true` while the location source is running, even when awaiting a usable fix. */
    val isActive: StateFlow<Boolean> = _isActive

    private val _isTransmitting = MutableStateFlow(false)

    /** Tracking is active and at least one location packet has been accepted by the BLE queue. */
    val isTransmitting: StateFlow<Boolean> = _isTransmitting

    private data class CachedFix(val location: GeoLocation, val validUntil: TimeMark)

    private var latest: CachedFix? = null
    private var hasSessionLocation = false
    private var reportedUnavailable = false
    private val lastSent = mutableMapOf<String, TimeMark>()
    private var collectJob: Job? = null
    private var periodicJob: Job? = null
    private var expiryJob: Job? = null
    private var failureJob: Job? = null
    private var retryJob: Job? = null
    private var nextRetryDelayMs = policy.sourceRetryInitialDelayMs

    /**
     * A device finished its handshake: make sure tracking runs and give the
     * camera a cached fix immediately instead of waiting for the next tick.
     */
    fun onDeviceReady(identifier: String) {
        startIfNeeded()
        sendImmediateIfCached(identifier.uppercase())
    }

    /**
     * Re-evaluate whether tracking should run. Called after disconnects or
     * app-enable toggles; stops everything when no ready session remains.
     */
    fun updateTracking() {
        lastSent.keys.retainAll(readySessions())
        if (readySessions().isEmpty() || !isTransmissionAllowed()) {
            stopUpdates()
        } else {
            startIfNeeded()
        }
    }

    /** Tear down all location state (service destroy / force shutdown). */
    fun shutdown() {
        stopUpdates()
        latest = null
    }

    private fun startIfNeeded() {
        if (!isTransmissionAllowed()) return
        if (readySessions().isEmpty()) return
        if (_isActive.value) return
        if (retryJob?.isActive == true) return

        val registered = try {
            source.start()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.e(error, msg = { "Location source registration failed" })
            source.stop()
            false
        }
        if (!registered) {
            log.e { "Location source could not be started, cannot begin transmission" }
            reportUnavailable()
            scheduleSourceRetry()
            return
        }
        _isActive.value = true
        log.i { "Starting location transmission" }

        collectJob = scope.launch {
            source.locations.collect { onNewLocation(it) }
        }
        failureJob = scope.launch {
            source.failures.collect {
                stopUpdates(resetRetry = false)
                reportUnavailable()
                scheduleSourceRetry()
            }
        }
        startResendIfNeeded()
    }

    private fun scheduleSourceRetry() {
        if (policy.sourceRetryInitialDelayMs == 0L || retryJob?.isActive == true ||
            readySessions().isEmpty() || !isTransmissionAllowed()
        ) return
        val delayMs = nextRetryDelayMs
        nextRetryDelayMs = if (delayMs >= policy.sourceRetryMaxDelayMs - delayMs) {
            policy.sourceRetryMaxDelayMs
        } else {
            delayMs * 2
        }
        retryJob = scope.launch {
            delay(delayMs.milliseconds)
            retryJob = null
            startIfNeeded()
        }
    }

    private fun startResendIfNeeded() {
        // Location callbacks must not keep postponing the camera's refresh.
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (isActive) {
                delay(policy.resendIntervalMs.milliseconds)
                val current = latest
                if (current != null && isFreshFix(current)) {
                    log.d { "Periodic timer – sending location to ready sessions" }
                    runCatching { sendToReadySessions(current) }
                        .onFailure { log.e(it, msg = { "Error sending location" }) }
                } else {
                    reportUnavailable()
                    // Wait for a usable callback instead of polling an absent/stale fix.
                    break
                }
            }
        }
    }

    private fun stopUpdates(resetRetry: Boolean = true) {
        retryJob?.cancel()
        retryJob = null
        if (resetRetry) nextRetryDelayMs = policy.sourceRetryInitialDelayMs
        _isTransmitting.value = false
        if (_isActive.value) {
            source.stop()
            log.i { "Stopped location transmission" }
        }
        collectJob?.cancel()
        collectJob = null
        periodicJob?.cancel()
        periodicJob = null
        expiryJob?.cancel()
        expiryJob = null
        failureJob?.cancel()
        failureJob = null
        _isActive.value = false
        hasSessionLocation = false
        reportedUnavailable = false
        latest = null
        lastSent.clear()
    }

    private fun onNewLocation(location: GeoLocation) {
        if (!_isActive.value || !isTransmissionAllowed()) return
        val now = nowMillis()
        // Reject stale, future-dated and invalid fixes before accuracy comparisons.
        if (location.timestampMillis > now || location.timestampMillis <= now - policy.maxFixAgeMs) return
        if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
            !location.horizontalAccuracyMeters.isFinite() || location.horizontalAccuracyMeters < 0
        ) return
        val current = latest
        if (!shouldUpdateLocation(location, current?.location)) return

        val remainingMs = policy.maxFixAgeMs - (now - location.timestampMillis)
        // A repeated provider cache entry is still the same sample. In particular,
        // a wall-clock correction must not renew its monotonic lifetime.
        val deadline = if (current?.location?.timestampMillis == location.timestampMillis) {
            current.validUntil
        } else {
            timeSource.markNow() + remainingMs.milliseconds
        }
        val fix = CachedFix(location, deadline)
        if (!isFreshFix(fix)) return
        val hadLocationBefore = hasSessionLocation
        hasSessionLocation = true
        reportedUnavailable = false
        latest = fix
        nextRetryDelayMs = policy.sourceRetryInitialDelayMs
        startResendIfNeeded()
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(-fix.validUntil.elapsedNow())
            if (latest === fix && !isFreshFix(fix)) {
                reportUnavailable()
                periodicJob?.cancel()
                periodicJob = null
            }
        }

        // Send right away when there was no usable fix yet; otherwise the
        // periodic loop picks the new fix up on its next tick.
        if (policy.sendOnUpdate || current == null || !isFreshFix(current)) {
            runCatching { sendToReadySessions(fix) }
                .onFailure { log.e(it, msg = { "Error sending location" }) }
        }

        if (!hadLocationBefore) {
            _events.trySend(LocationEvent.FirstFixAcquired)
        }
    }

    private fun sendImmediateIfCached(identifier: String) {
        if (!isTransmissionAllowed()) return
        if (identifier !in readySessions()) return
        latest?.let {
            if (isFreshFix(it)) {
                sendToDevice(identifier, it)
            }
        }
    }

    private fun sendToReadySessions(location: CachedFix) {
        readySessions().forEach { sendToDevice(it, location) }
    }

    private fun sendToDevice(identifier: String, fix: CachedFix) {
        if (!_isActive.value || !isTransmissionAllowed() || !isFreshFix(fix)) return
        if (lastSent[identifier]?.elapsedNow()?.inWholeMilliseconds?.let {
                it < policy.minSendIntervalMs
            } == true) return
        val location = fix.location
        val config = configFor(identifier) ?: LocationDataConfig(shouldSendTimeZoneAndDst = false)
        val packet = LocationPacketBuilder.buildLocationDataPacket(
            config,
            location.latitude,
            location.longitude,
            PlatformTimeZoneInfo(),
            now = clock.now(),
        )
        // A refresh has no value after the next refresh is due. Bound parked
        // packets by both the sample lifetime and one refresh interval, so a
        // blocked GATT lane cannot later flush a burst of old coordinates/times.
        val timeUntilExpiry = -fix.validUntil.elapsedNow()
        val refreshInterval = policy.resendIntervalMs.milliseconds
        val packetDeadline = if (timeUntilExpiry > refreshInterval) {
            fix.validUntil - (timeUntilExpiry - refreshInterval)
        } else {
            fix.validUntil
        }
        val queued = port.writeLocation(identifier, packet, packetDeadline)
        if (queued && _isActive.value) {
            lastSent[identifier] = timeSource.markNow()
            _isTransmitting.value = true
        }
    }

    private fun shouldUpdateLocation(new: GeoLocation, current: GeoLocation?): Boolean {
        current ?: return true
        if (new.timestampMillis < current.timestampMillis) return false
        if (policy.preferNewestFix) return true
        if (new.horizontalAccuracyMeters < 0 || current.horizontalAccuracyMeters < 0) return true

        val accuracyDifference = new.horizontalAccuracyMeters - current.horizontalAccuracyMeters
        if (accuracyDifference <= SonyBluetoothConstants.ACCURACY_THRESHOLD_METERS) return true

        val ageMs = new.timestampMillis - current.timestampMillis
        return ageMs / 1000 > MAX_IMMEDIATE_FIX_AGE_SECONDS
    }

    private fun isFreshFix(fix: CachedFix): Boolean {
        val now = nowMillis()
        return !fix.validUntil.hasPassedNow() && fix.location.timestampMillis <= now &&
            fix.location.timestampMillis > now - policy.maxFixAgeMs
    }

    private fun reportUnavailable() {
        _isTransmitting.value = false
        if (!reportedUnavailable) {
            reportedUnavailable = true
            log.w { "No fresh location available to send" }
            _events.trySend(LocationEvent.NoLocationAvailable)
        }
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    private companion object {
        const val MAX_IMMEDIATE_FIX_AGE_SECONDS = 30
    }
}
