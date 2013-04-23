
RokProx
-------

Rock some proxy shiz.

Testing distributed and networked systems is hard. Mocking helps to a point but nothing beats being able to deterministically reproduce network issues and test against a real network. RokProx is a simple lib that enables programmatic control over network connections by proxying them. This why we can interrupt / break / restore network connections in a controled and deterministic fasion in the tests. There are two modes that RokProx can operate in: Native and Server. 

In Native mode, you directly set up, manipulate and tear down proxied network connections through an internal dsl. This mode helps in integration tests.

In Server mode, RokProx runs as a server, then in tests you use the client dsl to create, manipulate and tear down proxied connections on the running server. This mode really helps in load tests.

The dsl for both modes is almost identical.

Getting Started
---------------

Currently, this only supports Scala 2.9. With a little bit of love and attention it should be able to support any JVM language and any language in server mode.

```scala

import com.github.bigtoast.rokprox._
import akka.util.duration._

// create a network proxy
val prox = RokProx.proxy("zk-proxy").from("localhost:1234").to("localhost:2181").build

// pause the byte stream..( buffer bytes)
prox.pause( 5 seconds )

// buffer bytes indefinitely 
prox.pause()

// unpause and flush bytes
prox.restore

// interrupt byte stream for 5 seconds.. ( drop bytes )
prox.interrupt( 5 seconds )

// interrupt indefinitely ( drop all bytes)
prox.interrupt()

// restore byte flow
prox.restore

// break all proxied connections
prox.break

// list individual connections.. ( returns a Future[Seq[CxnInfo]] )
prox.cxns.foreach( println )

// break a single proxied connection leaving others healthy
prox.breakCxn(cxnId)

```

Create a RokProx client and connect to a RokProx server
-------------------------------------------------------

```scala

import com.github.bigtoast.rokprox._
import akka.util.duration._

// connect to a server running on port 2552
val client = RokProx.client("localhost:2552").build

// create a proxy
val prox = client.builder.name("zk-proxy").from("localhost:1234").to("localhost:2181").build

// now manipulate the remote proxy as you would if it was a local proxy
prox.pause()

prox.restore

prox.pauseCxn(cxnId, 10 seconds)

``` 