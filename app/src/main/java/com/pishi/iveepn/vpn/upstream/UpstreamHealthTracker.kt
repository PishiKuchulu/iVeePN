package com.pishi.iveepn.vpn.upstream

import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class UpstreamHealthTracker(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    enum class Verdict {

        TARGET_FAILURE,

        SESSION_UNHEALTHY,

        SESSION_UNAUTHENTICATED,
    }

    private val timeoutTargets = LinkedHashSet<String>()

    /**
     * End of the post-(re)connect warm-up. A fresh session's first
     * seconds are dominated by the connection burst: every app on the
     * phone reconnects at once, the pool's handshake slots saturate,
     * and the 30s open cap makes dozens of those attempts expire in the
     * same second — on healthy servers too (observed on device:
     * region fr-prs proved itself with a successful exit-IP probe and
     * was still declared unhealthy ~30s after connect, and the forced
     * redial then made everything worse). Silence failures inside the
     * warm-up are therefore recorded as plain target failures and never
     * accumulate toward a session verdict. Credential rejections are
     * NOT warmed up: a 401/403 is conclusive at any age.
     */
    private var warmUpUntilMs: Long = nowMs() + WARM_UP_MS

    @Synchronized
    fun observeSuccess() {
        timeoutTargets.clear()
    }

    @Synchronized
    fun reset() {
        timeoutTargets.clear()
        warmUpUntilMs = nowMs() + WARM_UP_MS
    }

    @Synchronized
    fun observeFailure(target: String, cause: Throwable?): Verdict {

        if (invalidatesSession(cause)) {
            timeoutTargets.clear()
            return Verdict.SESSION_UNAUTHENTICATED
        }

        if (!isSilence(cause)) return Verdict.TARGET_FAILURE

        if (nowMs() < warmUpUntilMs) return Verdict.TARGET_FAILURE

        timeoutTargets.add(target)
        if (timeoutTargets.size < MAX_DISTINCT_TIMEOUT_TARGETS) return Verdict.TARGET_FAILURE

        timeoutTargets.clear()
        return Verdict.SESSION_UNHEALTHY
    }

    private fun isSilence(cause: Throwable?): Boolean = when (cause) {
        null -> true
        is UpstreamConnectTimeoutException -> true
        is SocketTimeoutException -> true
        is InterruptedIOException -> true
        else -> false
    }

    private fun invalidatesSession(cause: Throwable?): Boolean {
        val rejection = cause as? UpstreamConnectRejectedException ?: return false
        return rejection.statusCode in SESSION_FATAL_STATUS_CODES
    }

    companion object {

        const val MAX_DISTINCT_TIMEOUT_TARGETS = 10

        /** Post-(re)connect grace period; see [warmUpUntilMs]. */
        const val WARM_UP_MS = 45_000L

        val SESSION_FATAL_STATUS_CODES = setOf(401, 403, 407)

        val TARGET_UNREACHABLE_STATUS_CODES = setOf(502, 503, 504)
    }
}
