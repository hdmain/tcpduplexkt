package com.hdmain.tcpduplex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Cross-language interoperability tests against the Go reference implementation.
 */
class InteropTest {
    @Test
    @EnabledIf("goAvailable")
    fun kotlinClientGoServer() {
        val interopDir = findInteropDir() ?: return
        val port = freePort()
        val serverProc = goProcess(
            interopDir,
            "run", ".",
            "-mode", "server",
            "-listen", "127.0.0.1:$port",
            "-expect", "hello",
            "-reply", "world",
        ).start()

        waitForListen(port)
        TcpDuplex.dial("127.0.0.1:$port").use { cli ->
            cli.send("hello".toByteArray())
            val out = cli.receive()
            assertEquals("world", out.decodeToString())
        }

        assertEquals(0, serverProc.waitFor())
    }

    @Test
    @EnabledIf("goAvailable")
    fun goClientKotlinServer() {
        val interopDir = findInteropDir() ?: return
        val port = freePort()
        val ready = java.util.concurrent.CountDownLatch(1)

        thread {
            val ss = ServerSocket(port)
            ss.use { serverSocket ->
                ready.countDown()
                TcpDuplex.serveConn(serverSocket.accept()).use { srv ->
                    val msg = srv.receive()
                    assertEquals("ping", msg.decodeToString())
                    srv.send("pong".toByteArray())
                }
            }
        }

        ready.await(5, TimeUnit.SECONDS)
        val clientProc = goProcess(
            interopDir,
            "run", ".",
            "-mode", "client",
            "-addr", "127.0.0.1:$port",
            "-send", "ping",
            "-expect", "pong",
        ).start()

        assertEquals(0, clientProc.waitFor())
    }

    companion object {
        @JvmStatic
        fun goAvailable(): Boolean = ProcessBuilder("go", "version").start().waitFor() == 0

        private fun freePort(): Int {
            ServerSocket(0).use { return it.localPort }
        }

        private fun findInteropDir(): File? {
            var dir = File(System.getProperty("user.dir"))
            repeat(8) {
                val candidate = File(dir, "tcpduplex/interop")
                if (File(candidate, "go.mod").exists()) return candidate
                dir = dir.parentFile ?: return null
            }
            return null
        }

        private fun goProcess(dir: File, vararg args: String): ProcessBuilder {
            val pb = ProcessBuilder(listOf("go") + args)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            if (System.getProperty("os.name").lowercase().contains("win")) {
                pb.environment()["GOOS"] = "windows"
                pb.environment()["GOARCH"] = "amd64"
            }
            return pb
        }

        private fun waitForListen(port: Int) {
            repeat(100) {
                val inUse = runCatching {
                    ServerSocket(port).use { }
                    false
                }.getOrElse { true }
                if (inUse) return
                Thread.sleep(100)
            }
            error("Go server did not start on port $port")
        }
    }
}
