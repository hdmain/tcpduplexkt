package com.hdmain.tcpduplex.examples

import com.hdmain.tcpduplex.TcpDuplex
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

fun main() {
    val ss = ServerSocket(0)
    val port = ss.localPort
    val serverDone = CountDownLatch(1)

    thread {
        try {
            val raw = ss.accept()
            TcpDuplex.serveConn(raw).use { srv ->
                val msg = srv.receive()
                println("server received: ${msg.decodeToString()}")
                srv.send("world".toByteArray())
            }
        } finally {
            ss.close()
            serverDone.countDown()
        }
    }

    TcpDuplex.dial("127.0.0.1:$port").use { cli ->
        cli.send("hello".toByteArray())
        val out = cli.receive()
        println("client received: ${out.decodeToString()}")
    }

    serverDone.await()
}
