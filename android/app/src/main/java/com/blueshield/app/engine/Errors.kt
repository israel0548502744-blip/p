package com.blueshield.app.engine

/** Readable error text: type, message and the first stack frames (messages are often empty for framework errors). */
object Errors {
    fun describe(t: Throwable): String {
        val top = t.stackTrace.take(7).joinToString("\n") { "  at $it" }
        val cause = t.cause?.let { "\nCaused by: ${it.javaClass.simpleName}: ${it.message}\n" + it.stackTrace.take(4).joinToString("\n") { f -> "  at $f" } } ?: ""
        return "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}\n$top$cause"
    }
}
