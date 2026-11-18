package io.github.lumiagg.gestureevo.utils

internal enum class ActionWindowRoute { NORMAL, SYSTEM_FREEFORM, VIVO_SHARE, NUBIA_INTENT }

/** The launch target type must not change which ROM protocol is selected. */
internal fun actionWindowRoute(freeform: Boolean, vivo: Boolean, nubia: Boolean): ActionWindowRoute = when {
    !freeform -> ActionWindowRoute.NORMAL
    vivo -> ActionWindowRoute.VIVO_SHARE
    nubia -> ActionWindowRoute.NUBIA_INTENT
    else -> ActionWindowRoute.SYSTEM_FREEFORM
}
