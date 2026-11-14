package io.github.lumiagg.gestureevo.drop

/** The shell requires heartbeats so an abandoned worker exits even if a remote client retains stdin. */
internal object TouchStreamProcess {
    const val HEARTBEAT_MS = 10_000L
    fun arguments(devicePath: String): List<String> {
        require(devicePath.matches(Regex("/dev/input/event\\d+")))
        return listOf("/system/bin/sh", "-c", leaseScript, "evo-touch-stream", devicePath)
    }

    // Only the Java owner holds the stdin writer. EOF (including owner death) ends an idle getevent too.
    internal val leaseScript = """
        child=
        cleanup() {
            trap - EXIT HUP INT TERM
            if [ -n "${'$'}child" ]; then
                kill -KILL "${'$'}child" 2>/dev/null
                wait "${'$'}child" 2>/dev/null
            fi
        }
        trap cleanup EXIT
        trap 'exit 0' HUP INT TERM
        /system/bin/getevent -t "${'$'}1" </dev/null &
        child=${'$'}!
        while IFS= read -r -t 60 lease; do
            kill -0 "${'$'}child" 2>/dev/null || exit 1
        done
        exit 0
    """.trimIndent()
}
