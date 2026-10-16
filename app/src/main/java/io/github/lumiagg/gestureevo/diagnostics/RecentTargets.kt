package io.github.lumiagg.gestureevo.diagnostics

/** A bounded LRU, so long-running accessibility services always retain newly used targets. */
class RecentTargets(private val capacity: Int = 128) {
    private val values = LinkedHashMap<String, Unit>(capacity, .75f, true)
    @Synchronized fun remember(pkg: String?) {
        if (pkg == null || !pkg.matches(Regex("[A-Za-z0-9_.]{1,200}"))) return
        values[pkg] = Unit
        while (values.size > capacity) values.remove(values.keys.first())
    }
    @Synchronized fun snapshot(): List<String> = values.keys.toList().asReversed()
}
