package io.github.lumiagg.gestureevo.utils

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProcessRunnerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun command(mode: String): List<String> {
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        val java = File(requireNotNull(System.getProperty("java.home")), "bin/$executable").absolutePath
        val codeSource = requireNotNull(requireNotNull(ProcessFixture::class.java.protectionDomain).codeSource)
        val classes = File(codeSource.location.toURI()).absolutePath
        return listOf(java, "-cp", classes, ProcessFixture::class.java.name, mode)
    }

    @Test(timeout = 15000) fun largeStdoutAndStderrCannotBlockEachOther() {
        val result = runProcess(command("large"))
        assertTrue(result.isSuccess)
        assertEquals(256 * 1024, result.stdout.length)
        assertEquals(256 * 1024, result.stderr.length)
    }

    @Test(timeout = 15000) fun failedCommandKeepsExitCodeAndBothStreams() {
        val result = runProcess(command("fail"))
        assertEquals(7, result.exitCode)
        assertEquals("partial output", result.stdout)
        assertEquals("command failed", result.stderr)
        assertEquals(false to "command failed", result.asCommandResult())
    }

    @Test(timeout = 15000) fun successfulOutputIsUtf8AndNotMixedWithStderr() {
        assertEquals(true to "中文图标\n", runProcess(command("unicode")).asCommandResult())
    }

    @Test(timeout = 15000) fun suppliedEnvironmentAndWorkingDirectoryAreHonored() {
        val directory = temporaryFolder.newFolder("command-directory")
        val result = runProcess(command("environment"), listOf("GESTURE_EVO_TEST_VALUE=value=with=equals"), directory)
        assertEquals("value=with=equals|${directory.absolutePath}", result.stdout)
    }

    @Test(timeout = 15000) fun commandsReceiveEofOnStdin() {
        assertEquals("-1", runProcess(command("stdin")).stdout)
    }

    @Test(timeout = 15000) fun interruptionStopsWaitingAndPreservesInterruptFlag() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var interrupted = false
        val thread = Thread {
            entered.countDown()
            try {
                runProcess(command("wait"))
            } catch (_: InterruptedException) {
                interrupted = Thread.currentThread().isInterrupted
            } finally {
                finished.countDown()
            }
        }
        thread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        thread.interrupt()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(interrupted)
    }
}
