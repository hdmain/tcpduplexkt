package com.hdmain.tcpduplex.examples

import com.hdmain.tcpduplex.TcpDuplex
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

fun main() {
    ServerSocket(0).use { listener ->
        val port = listener.localPort
        val done = CountDownLatch(1)

        thread {
            try {
                TcpDuplex.accept(listener.accept()).use { server ->
                    val message = server.receive().decodeToString()
                    println("server received: $message")
                    server.send("world".toByteArray())
                }
            } finally {
                done.countDown()
            }
        }

        TcpDuplex.connect("127.0.0.1", port).use { client ->
            client.send("hello".toByteArray())
            println("client received: ${client.receive().decodeToString()}")
        }

        done.await()
    }
}
