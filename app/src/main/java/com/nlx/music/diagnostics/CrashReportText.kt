package com.nlx.music.diagnostics

import java.io.PrintWriter
import java.io.StringWriter

internal const val CRASH_TRACE_MAX_CHARS = 48_000
internal const val CRASH_OTHER_THREADS = 16
internal const val CRASH_OTHER_FRAMES = 8

/** Full causal chain, including causes and suppressed exceptions. */
internal fun formatThrowable(throwable: Throwable): String {
    val writer = StringWriter()
    throwable.printStackTrace(PrintWriter(writer))
    val text = writer.toString().trimEnd()
    return if (text.length <= CRASH_TRACE_MAX_CHARS) {
        text
    } else {
        text.take(CRASH_TRACE_MAX_CHARS) + "\n… trace truncated"
    }
}

internal fun formatThreadContext(thread: Thread): String {
    @Suppress("DEPRECATION")
    val id = thread.id
    return "name=${thread.name} id=$id state=${thread.state} priority=${thread.priority} daemon=${thread.isDaemon}"
}

internal fun formatOtherThreads(
    traces: Map<Thread, Array<StackTraceElement>>,
    skip: Thread?,
    maxThreads: Int = CRASH_OTHER_THREADS,
    maxFrames: Int = CRASH_OTHER_FRAMES,
): String {
    val selected = traces.entries
        .filter { (thread, _) -> thread != skip }
        .take(maxThreads)
    if (selected.isEmpty()) return "(no other threads)"
    return buildString {
        selected.forEach { (thread, frames) ->
            appendLine(formatThreadContext(thread))
            frames.take(maxFrames).forEach { frame -> appendLine("  at $frame") }
            if (frames.size > maxFrames) appendLine("  … ${frames.size - maxFrames} more")
        }
        val omitted = traces.size - (if (skip != null && skip in traces) 1 else 0) - selected.size
        if (omitted > 0) appendLine("… $omitted more threads")
    }.trimEnd()
}
