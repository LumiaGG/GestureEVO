package io.github.lumiagg.gestureevo.diagnostics

/** Extract typed codes only. Never parse exception messages, which may contain credentials or URLs. */
object LogErrors {
    fun fields(error: Throwable): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && seen.add(cause) && seen.size <= 5) {
            val current = cause
            val reason = when (current) {
                is java.net.SocketTimeoutException -> "network_timeout"
                is java.net.UnknownHostException -> "dns_failed"
                is java.net.ConnectException -> "connection_failed"
                is javax.net.ssl.SSLException -> "tls_failed"
                is SecurityException -> "permission_denied"
                is java.io.FileNotFoundException -> "file_unavailable"
                else -> null
            }
            if (reason != null) result["reason"] = reason
            // Closed class allowlists: do not invoke arbitrary Throwable getters or stringify causes.
            if (current.javaClass.name == "android.system.ErrnoException") {
                runCatching { current.javaClass.getField("errno").getInt(current) }.getOrNull()?.let { result["errno"] = it }
            }
            if (current.javaClass.name in setOf("com.thegrizzlylabs.sardineandroid.impl.SardineException", "com.thegrizzlylabs.sardineandroid.util.SardineException")) {
                runCatching { current.javaClass.getMethod("getStatusCode").invoke(current) as? Number }.getOrNull()
                    ?.toInt()?.takeIf { it in 100..599 }?.let { result["httpStatus"] = it }
            }
            cause = current.cause
        }
        return result
    }
}
