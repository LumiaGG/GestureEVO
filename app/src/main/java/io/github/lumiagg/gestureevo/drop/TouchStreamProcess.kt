package io.github.lumiagg.gestureevo.drop

import io.github.lumiagg.gestureevo.utils.app.shellArgument

/** Android toybox timeout kills the worker process group even if the Java owner is SIGKILLed. */
internal object TouchStreamProcess {
    const val DURATION_MS = 300_000L
    fun arguments(devicePath: String): List<String> {
        require(devicePath.matches(Regex("/dev/input/event\\d+")))
        return listOf("/system/bin/toybox", "timeout", "-s", "KILL", "300", "/system/bin/sh", "-c", leaseScript,
            "evo-touch-stream", devicePath)
    }

    fun rootArguments(device: TouchInputProtocol.Device): List<String> {
        val args = arguments(device.path).toMutableList()
        require(device.maxX > device.minX && device.maxY > device.minY)
        val header = "EVO_TOUCH ${device.minX} ${device.maxX} ${device.minY} ${device.maxY}"
        args[7] = "exec 4< \"${'$'}1\" || exit 1\nprintf '%s\\n' '$header'\n" + leaseScript
        return listOf("su", "-c", "exec " + args.joinToString(" ", transform = ::shellArgument))
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
