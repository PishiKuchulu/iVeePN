package com.pishi.iveepn

import com.pishi.iveepn.vpn.upstream.UpstreamConnectRejectedException
import com.pishi.iveepn.vpn.upstream.UpstreamHealthTracker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Verdict rules of [UpstreamHealthTracker], in particular the
 * post-connect warm-up: the connection burst at TUN-up makes dozens of
 * attempts expire together at the open cap even on healthy servers, so
 * silence failures inside the warm-up must never convict the session
 * (device log 2026-11-04, region fr-prs).
 */
class HealthTrackerTest {

    private var now = 1_000_000L
    private val tracker = UpstreamHealthTracker { now }

    private fun silence() = SocketTimeoutException("handshake timeout")

    private fun failDistinct(count: Int, startAt: Int = 0): UpstreamHealthTracker.Verdict {
        var verdict = UpstreamHealthTracker.Verdict.TARGET_FAILURE
        for (i in startAt until startAt + count) {
            verdict = tracker.observeFailure("target-$i:443", silence())
        }
        return verdict
    }

    @Test
    fun warmUpSuppressesSessionVerdict() {
        repeat(UpstreamHealthTracker.MAX_DISTINCT_TIMEOUT_TARGETS + 5) { i ->
            assertEquals(
                UpstreamHealthTracker.Verdict.TARGET_FAILURE,
                tracker.observeFailure("target-$i:443", silence()),
            )
        }
    }

    @Test
    fun verdictFiresAtThresholdAfterWarmUp() {
        now += UpstreamHealthTracker.WARM_UP_MS + 1
        assertEquals(
            UpstreamHealthTracker.Verdict.TARGET_FAILURE,
            failDistinct(UpstreamHealthTracker.MAX_DISTINCT_TIMEOUT_TARGETS - 1),
        )
        assertEquals(
            UpstreamHealthTracker.Verdict.SESSION_UNHEALTHY,
            tracker.observeFailure("target-last:443", silence()),
        )
    }

    @Test
    fun resetReArmsWarmUp() {
        now += UpstreamHealthTracker.WARM_UP_MS + 1
        tracker.reset()
        assertEquals(
            UpstreamHealthTracker.Verdict.TARGET_FAILURE,
            failDistinct(UpstreamHealthTracker.MAX_DISTINCT_TIMEOUT_TARGETS + 2),
        )
    }

    @Test
    fun successClearsAccumulatedTargets() {
        now += UpstreamHealthTracker.WARM_UP_MS + 1
        failDistinct(UpstreamHealthTracker.MAX_DISTINCT_TIMEOUT_TARGETS - 1)
        tracker.observeSuccess()
        assertEquals(
            UpstreamHealthTracker.Verdict.TARGET_FAILURE,
            failDistinct(UpstreamHealthTracker.MAX_DISTINCT_TIMEOUT_TARGETS - 1, startAt = 100),
        )
    }

    @Test
    fun credentialRejectionConvictsEvenDuringWarmUp() {
        val verdict = tracker.observeFailure(
            "target:443",
            UpstreamConnectRejectedException(statusCode = 403, authority = "target:443", message = "forbidden"),
        )
        assertEquals(UpstreamHealthTracker.Verdict.SESSION_UNAUTHENTICATED, verdict)
    }

    @Test
    fun nonSilenceFailuresNeverAccumulate() {
        now += UpstreamHealthTracker.WARM_UP_MS + 1
        repeat(UpstreamHealthTracker.MAX_DISTINCT_TIMEOUT_TARGETS + 3) { i ->
            assertEquals(
                UpstreamHealthTracker.Verdict.TARGET_FAILURE,
                tracker.observeFailure("target-$i:443", IOException("connection reset")),
            )
        }
        // And they leave no residue: a full set of silence targets is still required.
        assertEquals(
            UpstreamHealthTracker.Verdict.TARGET_FAILURE,
            failDistinct(UpstreamHealthTracker.MAX_DISTINCT_TIMEOUT_TARGETS - 1, startAt = 200),
        )
    }

    @Test
    fun nullCauseCountsAsSilence() {
        now += UpstreamHealthTracker.WARM_UP_MS + 1
        repeat(UpstreamHealthTracker.MAX_DISTINCT_TIMEOUT_TARGETS - 1) { i ->
            tracker.observeFailure("target-$i:443", null)
        }
        assertEquals(
            UpstreamHealthTracker.Verdict.SESSION_UNHEALTHY,
            tracker.observeFailure("target-last:443", null),
        )
    }
}
