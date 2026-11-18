package io.github.lumiagg.gestureevo.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionWindowRouteTest {
    @Test fun normalLaunchIgnoresRomFreeformProtocols() {
        assertEquals(ActionWindowRoute.NORMAL, actionWindowRoute(false, true, false))
        assertEquals(ActionWindowRoute.NORMAL, actionWindowRoute(false, false, true))
    }

    @Test fun vivoUsesShareProtocolForAllTargetTypes() {
        assertEquals(ActionWindowRoute.VIVO_SHARE, actionWindowRoute(true, true, false))
    }

    @Test fun nubiaRetainsItsIntentProtocol() {
        assertEquals(ActionWindowRoute.NUBIA_INTENT, actionWindowRoute(true, false, true))
    }

    @Test fun otherRomsUseSystemWindowOptions() {
        assertEquals(ActionWindowRoute.SYSTEM_FREEFORM, actionWindowRoute(true, false, false))
    }
}
