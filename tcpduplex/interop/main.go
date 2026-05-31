// Interop harness for Kotlin ↔ Go wire compatibility tests.
package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"time"

	"github.com/hdmain/tcpduplex"
)

func main() {
	mode := flag.String("mode", "", "server or client")
	listen := flag.String("listen", "", "server listen address")
	addr := flag.String("addr", "", "client dial address")
	expect := flag.String("expect", "", "expected received message")
	reply := flag.String("reply", "", "message to send back")
	send := flag.String("send", "", "client send payload")
	flag.Parse()

	switch *mode {
	case "server":
		runServer(*listen, *expect, *reply)
	case "client":
		runClient(*addr, *send, *expect)
	default:
		log.Fatal("mode must be server or client")
	}
}

func runServer(listen, expect, reply string) {
	ln, err := net.Listen("tcp", listen)
	if err != nil {
		log.Fatal(err)
	}
	defer ln.Close()

	_ = ln.(*net.TCPListener).SetDeadline(time.Now().Add(30 * time.Second))
	raw, err := ln.Accept()
	if err != nil {
		log.Fatal(err)
	}
	conn, err := tcpduplex.ServeConn(raw)
	if err != nil {
		log.Fatal(err)
	}
	defer conn.Close()

	msg, err := conn.Receive()
	if err != nil {
		log.Fatal(err)
	}
	if string(msg) != expect {
		log.Fatalf("expected %q got %q", expect, msg)
	}
	if reply != "" {
		if err := conn.Send([]byte(reply)); err != nil {
			log.Fatal(err)
		}
	}
}

func runClient(addr, send, expect string) {
	conn, err := tcpduplex.Dial(addr)
	if err != nil {
		log.Fatal(err)
	}
	defer conn.Close()

	if send != "" {
		if err := conn.Send([]byte(send)); err != nil {
			log.Fatal(err)
		}
	}
	if expect != "" {
		msg, err := conn.Receive()
		if err != nil {
			log.Fatal(err)
		}
		if string(msg) != expect {
			log.Fatalf("expected %q got %q", expect, msg)
		}
	}
	fmt.Println("ok")
	os.Exit(0)
}
