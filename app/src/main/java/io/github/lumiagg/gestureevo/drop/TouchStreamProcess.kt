package io.github.lumiagg.gestureevo.drop

/** Android toybox timeout kills the worker process group even if the Java owner is SIGKILLed. */
internal object TouchStreamProcess {
    const val DURATION_MS = 300_000L
    fun arguments(devicePath: String): List<String> {
        require(devicePath.matches(Regex("/dev/input/event\\d+")))
        return listOf("/system/bin/toybox", "timeout", "-s", "KILL", "300", "/system/bin/sh", "-c", leaseScript,
            "evo-touch-stream", devicePath)
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
        IFS= read -r lease
        exit 0
    """.trimIndent()
}
