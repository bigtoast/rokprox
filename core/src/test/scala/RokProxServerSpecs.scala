package com.github.bigtoast.rokprox

import org.scalatest._
import org.scalatest.matchers.ShouldMatchers

import akka.testkit.{ TestKit, TestActorRef }
import akka.actor.{ ActorRef, IO, Actor, ActorSystem, Props, IOManager }
import akka.dispatch.Await
import akka.util.ByteString
import akka.util.duration._
import IO._
import java.net.InetSocketAddress

class RokProxServerSpecs( _system :ActorSystem ) extends TestKit(_system) with WordSpec with ShouldMatchers with BeforeAndAfterAll  {

  def this() = this(ActorSystem("testers"))

  "RokProxServer" should {
    "accept a connection" in {
      object server extends Thread { override def run = RokProx.main( Array("3456") ) }
      server.start

      val client = RokProx.client("127.0.0.1:3456").build
      val res    = Await.result( client.proxies, 2 seconds )
      res should be (Nil)
      server.stop
    }

    "create new proxies" in {
      object server extends Thread { override def run = RokProx.main( Array("34567") ) }
      server.start

      val client = RokProx.client("127.0.0.1:34567").build

      val prox = client.builder.name("test-prox").from("127.0.0.1:1234").to("127.0.0.1:2345").build

      Await.result( prox.cxns, 2 seconds ) should be (Nil)

      val plist = Await.result( client.proxies, 2 seconds )
      plist should have size 1

      plist.head.name should be("test-prox")

      server.stop
    }
  }

}