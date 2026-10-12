package io.github.lumiagg.gestureevo.utils.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageStateCommandTest {
    @Test fun stateCommandsTargetTheCallingProfile() {
        assertEquals("pm enable --user 10 'com.example.app'", packageStateCommand("com.example.app", true, 10))
        assertEquals("pm disable-user --user 0 'com.example.app'", packageStateCommand("com.example.app", false, 0))
    }

    @Test fun shellMetacharactersRemainInsideOneArgument() {
        assertEquals("'com.example; echo secret'", shellArgument("com.example; echo secret"))
        assertEquals("'it'\"'\"'s'", shellArgument("it's"))
        assertEquals("'$(id)'", shellArgument("$(id)"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingPackageCannotProduceACommand() {
        packageStateCommand("", true, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidUserCannotProduceACommand() {
        packageStateCommand("com.example.app", false, -1)
    }
}
