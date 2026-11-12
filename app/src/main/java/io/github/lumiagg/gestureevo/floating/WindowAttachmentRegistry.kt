package io.github.lumiagg.gestureevo.floating

import java.util.Collections
import java.util.IdentityHashMap

/** WindowManager accepts addView before ViewRoot dispatches attachment; removal must track that ownership. */
internal class WindowAttachmentRegistry<V : Any> {
    class Request internal constructor(internal val onAttached: () -> Unit) {
        internal var notified = false
    }
    private val requests = IdentityHashMap<V, Request>()
    private val owned = Collections.newSetFromMap(IdentityHashMap<V, Boolean>())

    fun request(view: V, onAttached: () -> Unit): Request = Request(onAttached).also { requests[view] = it }
    fun isRequested(view: V): Boolean = requests.containsKey(view)
    fun isCurrent(view: V, request: Request): Boolean = requests[view] === request
    fun cancelRequest(view: V) { requests.remove(view) }

    fun submit(view: V, request: Request, isAttached: () -> Boolean, add: () -> Unit): Boolean {
        if (!isCurrent(view, request)) return false
        if (view !in owned && !isAttached()) add()
        owned.add(view)
        if (isAttached()) attached(view, request)
        return isCurrent(view, request)
    }

    fun attached(view: V, request: Request): Boolean {
        if (!isCurrent(view, request) || view !in owned) return false
        if (!request.notified) {
            request.notified = true
            request.onAttached()
        }
        return true
    }

    fun detach(view: V, isAttached: () -> Boolean, remove: () -> Unit) {
        cancelRequest(view)
        if (view in owned || isAttached()) {
            remove()
            owned.remove(view)
        }
    }
}
