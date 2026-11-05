package com.pishi.iveepn.data

import android.util.Log
import com.pishi.iveepn.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

object AppLogger {

    private const val MAX_ENTRIES = 2_000
    private const val MIN_PUBLISH_INTERVAL_MS = 200L

    private val buffer = ConcurrentLinkedDeque<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private val lastPublishAt = AtomicLong(0L)
    private val flushScheduled = AtomicBoolean(false)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "AppLogger-flush").apply { isDaemon = true }
    }

    // DateTimeFormatter is immutable and thread-safe, unlike the
    // SimpleDateFormat this replaced (shared across the UI thread and
    // the log-export IO paths).
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) {
        // Debug lines carry connection targets (host:port); they must
        // not reach logcat in release builds. The ProGuard
        // -assumenosideeffects rule strips the remaining direct Log.d
        // calls as a second layer.
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    // Log lines end up on the clipboard / in shared files via the Logs
    // screen, so email addresses (account identities) are masked at
    // this single choke point before a message is stored or forwarded
    // to logcat: first character of the local part and of the domain
    // survive, e.g. "a***@e***.com". Runs only on messages containing
    // '@', and allocates nothing for the common no-email case.
    //
    // The first character of each part may be ANY non-space,
    // non-'@' character — an earlier pattern required an alphanumeric
    // start, so unusual-but-valid shapes (local parts starting with
    // '.' or '-', quoted local parts) slipped through unmasked.
    // Plus-addressing, subdomains and short TLDs are covered by the
    // permissive middle sections; a dotted letter TLD is still required
    // so non-email "user@host" strings are left alone.
    private val EMAIL_PATTERN = Regex("""([^\s@])[^@\s]*@([^\s@])[^\s@]*\.([A-Za-z]{2,})""")

    internal fun maskEmails(message: String): String {
        if ('@' !in message) return message
        return EMAIL_PATTERN.replace(message) { match ->
            "${match.groupValues[1]}***@${match.groupValues[2]}***.${match.groupValues[3]}"
        }
    }

    /**
     * Mask an IP address for stored/logged output: an IPv4 address
     * keeps its first two octets ("51.254.*.*"), an IPv6 address keeps
     * its first two groups. Anything that is not an IP literal is
     * returned unchanged (callers use [maskTarget] for hostnames).
     */
    fun maskIp(value: String): String {
        val v = value.trim()
        if (v.contains(':')) {
            val groups = v.split(':')
            return groups.take(2).joinToString(":") + "::*"
        }
        val parts = v.split('.')
        val isIpv4 = parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all { it.isDigit() } && (part.toIntOrNull() ?: -1) in 0..255
        }
        return if (isIpv4) "${parts[0]}.${parts[1]}.*.*" else v
    }

    /**
     * Mask a connection target ("host:port") for stored/logged output:
     * IP literals go through [maskIp]; a hostname collapses to
     * "•••." + its last label (e.g. "•••.com"), so logs keep the shape
     * of a destination without recording where the user actually went.
     */
    fun maskTarget(host: String, port: Int? = null): String {
        val h = host.trim()
        val isIpLiteral = h.contains(':') || h.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all { it.isDigit() } && (part.toIntOrNull() ?: -1) in 0..255
            }
        }
        val maskedHost = when {
            isIpLiteral -> maskIp(h)
            h.contains('.') -> "•••." + h.substringAfterLast('.')
            else -> "•••"
        }
        return if (port != null) "$maskedHost:$port" else maskedHost
    }

    fun i(tag: String, message: String) {
        val masked = maskEmails(message)
        // Info lines routinely carry connection metadata (exit IP,
        // region, session events). They stay in the in-app buffer, but
        // they must not reach logcat in release builds, where any app
        // with log access could read a browsing-adjacent trail.
        if (BuildConfig.DEBUG) Log.i(tag, masked)
        log(LogLevel.INFO, tag, masked)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag, maskEmails(message), error) else Log.w(tag, maskEmails(message))
        log(LogLevel.WARN, tag, maskEmails(if (error != null) "$message: ${error.message}" else message))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, maskEmails(message), error) else Log.e(tag, maskEmails(message))
        log(LogLevel.ERROR, tag, maskEmails(if (error != null) "$message: ${error.message}" else message))
    }

    private val currentSize = java.util.concurrent.atomic.AtomicInteger(0)

    private val entryCounter = java.util.concurrent.atomic.AtomicLong(0)

    private fun log(level: LogLevel, tag: String, message: String) {
        buffer.addLast(LogEntry(entryCounter.incrementAndGet(), System.currentTimeMillis(), level, tag, message))
        var current = currentSize.incrementAndGet()

        while (current > MAX_ENTRIES) {
            if (buffer.pollFirst() != null) {
                current = currentSize.decrementAndGet()
            } else {
                currentSize.set(0)
                break
            }
        }
        schedulePublish()
    }

    private fun schedulePublish() {
        val now = System.currentTimeMillis()
        val last = lastPublishAt.get()
        if (now - last >= MIN_PUBLISH_INTERVAL_MS) {
            if (lastPublishAt.compareAndSet(last, now)) {
                _entries.value = buffer.toList()
            }
            return
        }
        if (flushScheduled.compareAndSet(false, true)) {
            val delayMs = (MIN_PUBLISH_INTERVAL_MS - (now - last)).coerceAtLeast(0)
            val scheduledOk = runCatching {
                scheduler.schedule({
                    flushScheduled.set(false)
                    lastPublishAt.set(System.currentTimeMillis())
                    _entries.value = buffer.toList()
                }, delayMs, TimeUnit.MILLISECONDS)
            }.isSuccess
            if (!scheduledOk) flushScheduled.set(false)
        }
    }

    fun clear() {
        buffer.clear()
        currentSize.set(0)
        _entries.value = emptyList()
    }

    fun exportAsText(): String = buffer.toList().joinToString("\n") { entry ->
        val ts = Instant.ofEpochMilli(entry.timestampMillis)
            .atZone(ZoneId.systemDefault())
            .format(timeFormat)
        "$ts ${entry.level.name.padEnd(5)} [${entry.tag}] ${entry.message}"
    }
}
