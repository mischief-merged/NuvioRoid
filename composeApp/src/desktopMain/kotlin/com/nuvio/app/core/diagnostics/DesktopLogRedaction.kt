package com.nuvio.app.core.diagnostics

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

internal class RedactingLogWriter(private val destination: LogWriter) : LogWriter() {
    override fun isLoggable(tag: String, severity: Severity): Boolean = destination.isLoggable(tag, severity)
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val detail = throwable?.let { "\n${it.stackTraceToString()}" }.orEmpty()
        destination.log(severity, CredentialRedactor.redact(message + detail), CredentialRedactor.redact(tag), null)
    }
}

/** Buffer complete lines, including split UTF-8 and split secrets. Oversized lines are omitted as a whole. */
internal class RedactingOutputStream(private val destination: OutputStream) : OutputStream() {
    private val pending = ByteArrayOutputStream()
    private var oversized = false
    @Synchronized override fun write(value: Int) {
        if (value == 10) {
            val line = if (oversized) "[long log line omitted]" else CredentialRedactor.redact(pending.toString(Charsets.UTF_8))
            destination.write((line + "\n").toByteArray(Charsets.UTF_8))
            pending.reset()
            oversized = false
        } else if (pending.size() < 65536 && !oversized) pending.write(value)
        else { pending.reset(); oversized = true }
    }
    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        for (index in offset until offset + length) write(bytes[index].toInt() and 255)
    }
    @Synchronized override fun flush() { destination.flush() }
    @Synchronized override fun close() {
        if (pending.size() > 0 || oversized) write(10)
        flush()
    }
}

internal object DesktopLogRedaction {
    private var installed = false
    @Synchronized fun install() {
        if (installed) return
        installed = true
        Logger.setLogWriters(Logger.config.logWriterList.map(::RedactingLogWriter))
        System.setOut(PrintStream(RedactingOutputStream(System.out), true, Charsets.UTF_8))
        System.setErr(PrintStream(RedactingOutputStream(System.err), true, Charsets.UTF_8))
    }
}
