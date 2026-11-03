package com.pishi.iveepn.ui

import android.content.Context
import com.pishi.iveepn.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Maps raw failures to short, human, actionable messages (audit row 13).
 * Technical detail (exception text, HTTP codes, hashes) belongs in the log
 * via AppLogger — never on screen. Callers pass any context; the strings
 * resolve in that context's locale.
 */
object UiErrors {

    private val HTTP_CODE = Regex("""HTTP (\d{3})""")

    fun friendlyMessage(context: Context, error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }

        HTTP_CODE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
            return context.getString(
                when {
                    code == 401 || code == 403 -> R.string.err_http_auth
                    code == 429 -> R.string.err_http_rate_limited
                    code in 500..599 -> R.string.err_http_server
                    else -> R.string.err_http_generic
                },
            )
        }
        if (chain.any { it is UnknownHostException } ||
            text.contains("Unable to resolve host", ignoreCase = true)
        ) {
            return context.getString(R.string.err_network_unreachable)
        }
        if (chain.any { it is SocketTimeoutException } ||
            text.contains("timed out", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true)
        ) {
            return context.getString(R.string.err_timeout)
        }
        if (text.contains("checksum", ignoreCase = true) ||
            text.contains("SHA-256", ignoreCase = true) ||
            text.contains("digest", ignoreCase = true) ||
            text.contains("not signed", ignoreCase = true)
        ) {
            return context.getString(R.string.err_update_integrity)
        }
        if (chain.any { it is ConnectException } ||
            text.contains("Network is unreachable", ignoreCase = true) ||
            text.contains("Connection refused", ignoreCase = true)
        ) {
            return context.getString(R.string.err_network_unreachable)
        }
        return context.getString(R.string.err_unknown)
    }
}
