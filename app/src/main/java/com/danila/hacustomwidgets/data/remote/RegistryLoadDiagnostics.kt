package com.danila.hacustomwidgets.data.remote

import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicLong

/** Observation only: never controls requests, completion, parsing, or socket lifetime. */
internal class RegistryLoadDiagnostics(
    private val token: String,
    private val emit: (error: Boolean, line: String) -> Unit,
) {
    data class State(val stage: String = "connecting", val requestId: Int? = null, val command: String? = null)
    @Volatile var state = State()
        private set
    private val loadId = ids.incrementAndGet()

    fun stage(value: String) {
        state = state.copy(stage = value)
        event("stage")
    }

    fun command(id: Int, type: String) {
        state = State("awaiting_response", id, type)
        event("send")
    }

    fun event(value: String) = write(false, value)

    fun failure(event: String, error: Throwable, code: Int? = null, message: String? = null) {
        write(true, "$event exceptionClass=${error.javaClass.name} exceptionMessage=${error.message} httpCode=$code httpMessage=$message")
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        // One log entry per stack line avoids logcat's per-entry truncation.
        redact(trace).lineSequence().forEach { write(true, "stack $it") }
    }

    private fun write(error: Boolean, value: String) {
        val snapshot = state
        emit(error, redact("load=$loadId stage=${snapshot.stage} requestId=${snapshot.requestId} command=${snapshot.command} $value"))
    }

    internal fun redact(value: String): String {
        var safe = if (token.isEmpty()) value else value.replace(token, "[REDACTED]")
        safe = safe.replace(Regex("(?i)(authorization\\s*[:=]\\s*)([^\\r\\n]+)"), "$1[REDACTED]")
        safe = safe.replace(Regex("(?i)(bearer\\s+)[^\\s\\\"',;]+"), "$1[REDACTED]")
        safe = safe.replace(Regex("(?i)([\\\"']?(?:access_token|refresh_token|token|password|client_secret|api_key)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,}&]+)"), "$1[REDACTED]")
        safe = safe.replace(Regex("(https?://)[^/@\\s]+:[^/@\\s]+@"), "$1[REDACTED]@")
        return safe
    }

    companion object {
        const val TAG = "HARegistryDiag"
        private val ids = AtomicLong()
    }
}
