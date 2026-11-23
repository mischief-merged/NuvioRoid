package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Downloads that never make the rest of the Mac's internet worse.
 *
 * 1. Yielding (YieldController). While downloads run, the round trip to the download host is measured
 *    every couple of seconds with a TCP connection that is opened and closed at once (no data, no root).
 *    The lowest round trips seen are the baseline: the link with empty queues. When the recent round
 *    trip rises well above it, queues are building on the link, which is what makes web pages and calls
 *    slow; the shared download rate is then cut below what downloads were getting. When the round trip is
 *    back near the baseline, the rate grows again slowly, and the limit goes once it no longer binds.
 *    So downloads settle at what the link has spare. The user's own speed limit stays the ceiling.
 * 2. Slow network (SlowNetworkJudge). When what downloads get stays under a floor for a minute,
 *    automatic downloads wait; downloads the user started go on, still yielding. A trial run every few
 *    minutes finds out whether it got better.
 * 3. Blocked network (BlockedNetworkJudge). A network that answers with a sign-in page, breaks secure
 *    connections, or refuses downloads from every host while other requests work makes downloads wait
 *    instead of retrying in a loop. It is looked at again every few minutes. A wait is a pause: partial
 *    files are kept and continued.
 */

/** How often the downloads are looked at, and the round trip measured, while any transfer runs. */
internal const val PoliteTickMs = 2_000L

/** The baseline is the lowest round trip of each minute, over this many minutes (as in LEDBAT). */
internal const val BaselineMinutes = 10

/** The recent round trip is the lowest of this many last samples, so one late answer is not a queue. */
internal const val RecentSamples = 3

/**
 * Queuing delay that counts as other traffic suffering: a round trip this much above the baseline. The
 * larger of 60 ms and a quarter of the baseline, at most 200 ms: 60 ms is about where calls and pages
 * start to feel it, and a satellite link with a long baseline has more jitter than that.
 */
internal fun queueTargetMs(baselineMs: Long): Long = maxOf(60L, baselineMs / 4).coerceAtMost(200L)

/** A rate cut takes downloads to this part of what they were getting, so the queue drains. */
internal const val YieldCutFactor = 0.7

/** Each calm look grows the rate by this factor (about 7% a second): slower than any cut, as in TCP. */
internal const val YieldGrowFactor = 1.15

/** No growth this long after a cut, so the effect of the cut is seen first. */
internal const val YieldCooldownMs = 6_000L

/** The limit goes when it is this many times what downloads get, and they have been calm this long. */
internal const val YieldReleaseHeadroom = 2.0
internal const val YieldReleaseCalmMs = 30_000L

/** Downloads are never yielded below this rate (64 kB/s, about half a megabit): they keep going, barely. */
internal const val YieldMinBytesPerSecond = 64_000L

/** A round trip probe that gets no answer within this counts as this long a round trip: a full queue. */
internal const val ProbeTimeoutMs = 1_500L

/** A host's round trips are judged only once this many were measured: before that it has no baseline of its own. */
internal const val WarmupSamples = 5

/** After this many probes in a row that got no answer at all, the limit eases off: there is no evidence of a queue. */
internal const val FailedProbesBeforeEasing = 3

/** What is known about the round trips to one host. */
private class HostRoundTrips {
    // The lowest round trip of each minute, oldest first: (minute, round trip).
    val minuteMinimums = ArrayDeque<Pair<Long, Long>>()
    val recent = ArrayDeque<Long>()
    var samples = 0
    var lastSeenMs = 0L

    val baselineMs: Long? get() = minuteMinimums.minOfOrNull { it.second }

    fun add(nowMs: Long, rttMs: Long) {
        lastSeenMs = nowMs
        samples++
        val minute = nowMs / 60_000L
        val last = minuteMinimums.lastOrNull()
        if (last != null && last.first == minute) {
            if (rttMs < last.second) minuteMinimums[minuteMinimums.lastIndex] = minute to rttMs
        } else {
            minuteMinimums.addLast(minute to rttMs)
        }
        while (minuteMinimums.isNotEmpty() && minuteMinimums.first().first <= minute - BaselineMinutes) minuteMinimums.removeFirst()
        recent.addLast(rttMs)
        while (recent.size > RecentSamples) recent.removeFirst()
    }
}

/**
 * Works out the download rate that leaves the link to other traffic. Times in milliseconds on any
 * monotonic clock. Pure: it looks at nothing but its arguments.
 *
 * Each host is judged against its own baseline (a far host has a long round trip that is no queue), and
 * only after [WarmupSamples] of its own. The limit is one for all downloads: they share the link.
 */
internal class YieldController {
    private val hosts = mutableMapOf<String, HostRoundTrips>()
    private var lastCutAtMs: Long? = null
    private var calmSinceMs: Long? = null
    private var failedProbes = 0

    /** The rate downloads are held to, in bytes a second. Null: not held. */
    var capBytesPerSecond: Long? = null
        private set

    /** The baseline of [host], or of the only host measured when none is named. */
    fun baselineMs(host: String? = null): Long? =
        (if (host != null) hosts[host] else hosts.values.singleOrNull())?.baselineMs

    val baselineMs: Long? get() = baselineMs(null)

    /**
     * One look: [rttMs] is the round trip to [host] just measured (null when the probe got no answer at all),
     * and [throughputBytesPerSecond] what downloads got since the last look. Returns the rate to hold to.
     */
    fun onSample(nowMs: Long, rttMs: Long?, throughputBytesPerSecond: Long, host: String = ""): Long? {
        if (rttMs == null || rttMs < 0L) {
            // Probes that fail say nothing about a queue: after a few, the limit eases off as on a calm link.
            if (++failedProbes >= FailedProbesBeforeEasing) ease(nowMs, throughputBytesPerSecond)
            return capBytesPerSecond
        }
        failedProbes = 0
        hosts.entries.removeAll { (name, trips) -> name != host && nowMs - trips.lastSeenMs > BaselineMinutes * 60_000L }
        val trips = hosts.getOrPut(host) { HostRoundTrips() }
        trips.add(nowMs, rttMs)
        // Too few samples of this host to tell a queue from its normal round trip.
        if (trips.samples < WarmupSamples || trips.recent.size < RecentSamples) return capBytesPerSecond

        val baseline = trips.baselineMs ?: return capBytesPerSecond
        val queueMs = trips.recent.min() - baseline
        val target = queueTargetMs(baseline)
        val cap = capBytesPerSecond
        when {
            queueMs > target -> {
                // Below what downloads were really getting, and below the limit already set.
                val getting = throughputBytesPerSecond.takeIf { it > 0L } ?: cap ?: return cap
                val from = if (cap != null) minOf(cap, getting) else getting
                capBytesPerSecond = maxOf(YieldMinBytesPerSecond, (from * YieldCutFactor).toLong())
                lastCutAtMs = nowMs
                calmSinceMs = null
            }
            queueMs * 2 <= target -> ease(nowMs, throughputBytesPerSecond)
            // Between: hold the rate where it is.
            else -> calmSinceMs = null
        }
        return capBytesPerSecond
    }

    /** A calm look: the rate grows after the cooldown, and the limit goes once it no longer binds and stayed calm. */
    private fun ease(nowMs: Long, throughputBytesPerSecond: Long) {
        val cap = capBytesPerSecond ?: return
        val calmSince = calmSinceMs ?: nowMs.also { calmSinceMs = it }
        val cooled = lastCutAtMs?.let { nowMs - it >= YieldCooldownMs } ?: true
        if (!cooled) return
        val grown = (cap * YieldGrowFactor).toLong() + 1L
        val unbound = throughputBytesPerSecond > 0L && grown >= throughputBytesPerSecond * YieldReleaseHeadroom
        capBytesPerSecond = if (unbound && nowMs - calmSince >= YieldReleaseCalmMs) null else grown
    }

    /** No transfer runs: the limit goes (the next transfer starts free), the baselines are kept. */
    fun idle() {
        capBytesPerSecond = null
        hosts.values.forEach { it.recent.clear() }
        lastCutAtMs = null
        calmSinceMs = null
        failedProbes = 0
    }
}

/** Under this many bytes a second (2 Mbit/s) a network is too slow for automatic downloads: an episode takes over an hour. */
internal const val SlowFloorBytesPerSecond = 250_000L

/** ...when that lasts this long, so a slow minute between fast ones does not count. */
internal const val SlowSustainMs = 60_000L

/** The first seconds of transfers are not judged: a connection starts slow. */
internal const val SlowGraceMs = 15_000L

/** While automatic downloads wait for a slow network, they are let run again this often, to see whether it got better. */
internal const val SlowRecheckMs = 5 * 60_000L

/** Downloads the user runs getting over the floor this long ends the wait early. */
internal const val SlowRecoverMs = 30_000L

/** Decides when the network is too slow for automatic downloads. Pure. */
internal class SlowNetworkJudge {
    private var activeSinceMs: Long? = null
    private var slowSinceMs: Long? = null
    private var fastSinceMs: Long? = null
    private var heldAtMs: Long? = null

    val slow: Boolean get() = heldAtMs != null

    /**
     * One look. [transfersActive]: some download is transferring. [userCapBytesPerSecond]: the user's
     * own limit; one below the floor is the user's choice and never makes a network slow. [limitedByYield]:
     * this app was holding downloads back to leave the link to others, so what they got is not what the
     * network can give, and the look is not judged: yielding never makes a network slow by itself.
     */
    fun onSample(
        nowMs: Long,
        throughputBytesPerSecond: Long,
        transfersActive: Boolean,
        userCapBytesPerSecond: Long?,
        limitedByYield: Boolean = false,
    ): Boolean {
        val held = heldAtMs
        if (held != null) {
            if (nowMs - held >= SlowRecheckMs) {
                // A trial: automatic downloads run again and are judged afresh.
                reset()
                activeSinceMs = if (transfersActive) nowMs else null
                return false
            }
            if (transfersActive && throughputBytesPerSecond >= SlowFloorBytesPerSecond) {
                val since = fastSinceMs ?: nowMs.also { fastSinceMs = it }
                if (nowMs - since >= SlowRecoverMs) reset()
            } else {
                fastSinceMs = null
            }
            return slow
        }
        if (!transfersActive || (userCapBytesPerSecond != null && userCapBytesPerSecond < SlowFloorBytesPerSecond)) {
            activeSinceMs = null
            slowSinceMs = null
            return false
        }
        val activeSince = activeSinceMs ?: nowMs.also { activeSinceMs = it }
        if (nowMs - activeSince < SlowGraceMs) return false
        if (limitedByYield) {
            slowSinceMs = null
            return false
        }
        if (throughputBytesPerSecond >= SlowFloorBytesPerSecond) {
            slowSinceMs = null
            return false
        }
        val since = slowSinceMs ?: nowMs.also { slowSinceMs = it }
        if (nowMs - since >= SlowSustainMs) heldAtMs = nowMs
        return slow
    }

    fun reset() {
        activeSinceMs = null
        slowSinceMs = null
        fastSinceMs = null
        heldAtMs = null
    }
}

/** How a transfer failed, in the ways a network that blocks downloads makes them fail. */
internal enum class TransferFailureKind {
    /** A web page arrived where the video was asked for: a sign-in page of the network, usually. */
    PageInsteadOfFile,

    /** The secure connection could not be made: the network may intercept it. */
    SecureConnection,

    /** The connection was reset or closed before the file. */
    Reset,
}

/** What a look at a known page outside the download hosts found. */
internal enum class NetworkCheck {
    /** The page came back as expected: the network lets requests through. */
    Open,

    /** Another page came back: a sign-in page of the network. */
    SignInPage,

    /** The secure connection to it failed: the network intercepts secure connections. */
    SecureConnectionBroken,

    /** It could not be reached at all: the internet is down, which is not a blocked network. */
    Unreachable,

    /** It answered with something else (an error of its own, a redirect within Apple): nothing can be told. */
    Unknown,
}

/**
 * What the look at Apple's connectivity page found, from its answer. Only the expected "Success" page is
 * [NetworkCheck.Open]; a 200 with another page, or a redirect to another host, is a sign-in page; any other
 * answer (429, 5xx, a redirect within Apple) tells nothing.
 */
internal fun networkCheckOf(status: Int, body: String?, redirectHost: String?): NetworkCheck = when {
    status == 200 && body.orEmpty().contains("<TITLE>Success</TITLE>", ignoreCase = true) -> NetworkCheck.Open
    status == 200 -> NetworkCheck.SignInPage
    status in 300..399 && redirectHost != null && !redirectHost.lowercase().let { it == "apple.com" || it.endsWith(".apple.com") } ->
        NetworkCheck.SignInPage
    else -> NetworkCheck.Unknown
}

/** Second-level labels under which a country code makes the registrable domain three labels long ("files.co.uk"). */
private val SECOND_LEVEL_SUFFIXES = setOf("co", "com", "net", "org", "ac", "gov", "edu", "ne", "or", "go")

/**
 * The domain a host belongs to, so that two hosts of one provider count as one: "edge-1.cdn.provider.com"
 * is "provider.com", "files.other.co.uk" is "other.co.uk". An approximation of the public suffix list that
 * errs towards counting hosts as one provider. An address is itself.
 */
internal fun registrableDomain(host: String): String {
    val name = host.trim().trimEnd('.').lowercase()
    if (name.isEmpty() || name.contains(':') || name.all { it.isDigit() || it == '.' }) return name
    val labels = name.split('.')
    if (labels.size <= 2) return name
    val take = if (labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL_SUFFIXES) 3 else 2
    return labels.takeLast(take).joinToString(".")
}

/** Failures from this many providers (registrable domains) in [BlockedWindowMs], with no bytes from any, while requests work, is a network that blocks downloads. */
internal const val BlockedMinHosts = 2
internal const val BlockedWindowMs = 3 * 60_000L

/** The network is looked at no more often than this, whatever fails. */
internal const val NetworkCheckMinIntervalMs = 2 * 60_000L

/** While downloads wait for a blocked network, it is looked at again this often. */
internal const val BlockedRecheckMs = 5 * 60_000L

/** Decides when the network blocks downloads. Pure: the look at the network is the caller's, its result given here. */
internal class BlockedNetworkJudge {
    private val failures = mutableListOf<Triple<Long, String, TransferFailureKind>>()
    private var lastBytesAtMs: Long? = null
    private var lastCheckAtMs: Long? = null
    private var blockedAtMs: Long? = null

    val blocked: Boolean get() = blockedAtMs != null

    fun onBytes(nowMs: Long) {
        lastBytesAtMs = nowMs
        if (blocked) blockedAtMs = null
    }

    fun onFailure(nowMs: Long, host: String, kind: TransferFailureKind) {
        failures += Triple(nowMs, registrableDomain(host), kind)
        failures.removeAll { nowMs - it.first > BlockedWindowMs }
    }

    /** Whether the network is to be looked at now: after a failure that a blocking network causes, or to see whether a block ended. */
    fun checkDue(nowMs: Long): Boolean {
        val last = lastCheckAtMs
        val minInterval = if (blocked) BlockedRecheckMs else NetworkCheckMinIntervalMs
        if (last != null && nowMs - last < minInterval) return false
        return blocked || failures.any { nowMs - it.first <= BlockedWindowMs }
    }

    /** What the look at the network found. Returns whether the network blocks downloads now. */
    fun onCheck(nowMs: Long, check: NetworkCheck): Boolean {
        lastCheckAtMs = nowMs
        val recent = failures.filter { nowMs - it.first <= BlockedWindowMs }
        val noBytes = lastBytesAtMs?.let { nowMs - it > BlockedWindowMs } ?: true
        val wasBlocked = blocked
        val nowBlocked = when (check) {
            // Downloads that still receive bytes are not blocked, whatever the page says.
            NetworkCheck.SignInPage -> noBytes
            NetworkCheck.SecureConnectionBroken -> noBytes && (recent.any { it.third == TransferFailureKind.SecureConnection } || wasBlocked)
            NetworkCheck.Unreachable -> false
            NetworkCheck.Unknown -> wasBlocked
            NetworkCheck.Open -> !wasBlocked && noBytes && recent.map { it.second }.distinct().size >= BlockedMinHosts
        }
        // Failures while the internet was down say nothing about a block once it is back.
        if (check == NetworkCheck.Unreachable) failures.clear()
        blockedAtMs = if (nowBlocked) (blockedAtMs ?: nowMs) else null
        // A block that ended, or a trial after one: failures from before say nothing about what comes now.
        if (wasBlocked && !nowBlocked) failures.clear()
        return blocked
    }
}

/** A host a running transfer talks to, for the round trip probe. */
internal data class TransferEndpoint(val host: String, val port: Int)

/**
 * What the transfers are doing, for the yield controller: which hosts they talk to and how many bytes
 * arrived. Thread safe: transfers report from their own threads.
 */
internal class DownloadTraffic {
    private val lock = SynchronizedObject()
    private val active = mutableListOf<Pair<Long, TransferEndpoint>>()
    private var nextToken = 0L
    private var bytes = 0L
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    fun begin(endpoint: TransferEndpoint): Long = synchronized(lock) {
        val token = ++nextToken
        active += token to endpoint
        _activeCount.value = active.size
        token
    }

    fun end(token: Long) = synchronized(lock) {
        active.removeAll { it.first == token }.also { _activeCount.value = active.size }
    }

    fun add(count: Int) = synchronized(lock) { bytes += count }

    /** The bytes since the last call. */
    fun takeBytes(): Long = synchronized(lock) { bytes.also { bytes = 0L } }

    /** The host of the transfer that started last, or null when none runs. */
    fun latestEndpoint(): TransferEndpoint? = synchronized(lock) { active.lastOrNull()?.second }

    companion object {
        val app = DownloadTraffic()
    }
}

/** What the Downloads screen says about yielding. */
internal data class PoliteState(
    /** Downloads are slowed down so other apps stay fast. */
    val yielding: Boolean = false,
)

/**
 * Runs the three rules, one [tick] at a time (every [PoliteTickMs] while the app runs). Everything it
 * touches is given to it, so tests drive it with their own clock, probe and network.
 */
internal class PoliteDownloads(
    private val clock: () -> Long,
    private val probeRoundTrip: suspend (TransferEndpoint) -> Long?,
    private val checkNetwork: suspend () -> NetworkCheck,
    private val traffic: DownloadTraffic,
    private val enabled: () -> Boolean,
    private val userCapBytesPerSecond: () -> Long?,
    private val setYieldCap: (Long?) -> Unit,
    private val reportQuality: (LimitedConnectionReason?) -> Unit,
) {
    private val lock = SynchronizedObject()
    private val controller = YieldController()
    private val slowJudge = SlowNetworkJudge()
    private val blockedJudge = BlockedNetworkJudge()
    private var lastTickAtMs: Long? = null
    private var lastProbeAtMs: Long? = null
    private var reported: LimitedConnectionReason? = null

    /** How many round trip probes were made. For tests and the log. */
    var probes = 0
        private set

    private val _state = MutableStateFlow(PoliteState())
    val state: StateFlow<PoliteState> = _state.asStateFlow()

    /** A transfer failed in a way a network that blocks downloads causes. */
    fun onTransferFailure(host: String, kind: TransferFailureKind) = synchronized(lock) {
        blockedJudge.onFailure(clock(), host, kind)
    }

    suspend fun tick() {
        val now = clock()
        val elapsed = lastTickAtMs?.let { now - it }?.takeIf { it > 0L }
        lastTickAtMs = now
        val bytes = traffic.takeBytes()
        val throughput = if (elapsed != null) bytes * 1_000L / elapsed else 0L
        if (bytes > 0L) synchronized(lock) { blockedJudge.onBytes(now) }
        val endpoint = traffic.latestEndpoint()

        if (synchronized(lock) { blockedJudge.checkDue(now) }) {
            val check = runCatching { checkNetwork() }.getOrDefault(NetworkCheck.Unreachable)
            synchronized(lock) { blockedJudge.onCheck(clock(), check) }
        }

        if (!enabled()) {
            controller.idle()
            slowJudge.reset()
            setYieldCap(null)
        } else if (endpoint == null) {
            controller.idle()
            slowJudge.onSample(now, 0L, transfersActive = false, userCapBytesPerSecond = userCapBytesPerSecond())
            setYieldCap(null)
        } else {
            // One probe a tick at most, and only while a transfer runs.
            val rtt = if (lastProbeAtMs?.let { now - it < PoliteTickMs } == true) {
                null
            } else {
                lastProbeAtMs = now
                probes++
                runCatching { probeRoundTrip(endpoint) }.getOrNull()
            }
            val limitedAtLook = controller.capBytesPerSecond != null
            setYieldCap(controller.onSample(now, rtt, throughput, host = "${endpoint.host}:${endpoint.port}"))
            // What downloads got while this app limited them says nothing about the network: not judged.
            slowJudge.onSample(
                now, throughput,
                transfersActive = true,
                userCapBytesPerSecond = userCapBytesPerSecond(),
                limitedByYield = limitedAtLook || controller.capBytesPerSecond != null,
            )
        }
        _state.value = PoliteState(yielding = controller.capBytesPerSecond != null)

        val quality = when {
            blockedJudge.blocked -> LimitedConnectionReason.NetworkBlocksDownloads
            slowJudge.slow -> LimitedConnectionReason.SlowConnection
            else -> null
        }
        if (quality != reported) {
            reported = quality
            reportQuality(quality)
        }
    }
}
