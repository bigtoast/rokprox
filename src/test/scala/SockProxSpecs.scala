package com.github.bigtoast.rokprox

import org.scalatest._
import org.scalatest.matchers.ShouldMatchers

import RokProx._

import akka.testkit.{ TestKit, TestActorRef }
import akka.actor.{ ActorRef, IO, Actor, ActorSystem, Props, IOManager }
import akka.util.ByteString
import akka.util.duration._
import IO._
import java.net.InetSocketAddress

class SockProxSpecs( _system :ActorSystem ) extends TestKit(_system) with WordSpec with ShouldMatchers with BeforeAndAfterAll  {

	def this() = this(ActorSystem("testers"))

	implicit val dur = 2 seconds

	override def afterAll {
    	system.shutdown()
  	}

  	"Paused socked" should {
  		"buffer bytes" in {
  			val hello = ByteString("hello")
  			val nurse = ByteString(" nurse")
  			// SocketHandle( owner, ioManager )
  			val paused = PausedSock( null, null, true )

  			paused.buffered should be (ByteString.empty)

  			val p2 = paused.write(hello).write(nurse)

  			p2.buffered should be (ByteString("hello nurse"))
  		}

  		"transition to open and flush bytes on restore" in {
  			val paused = PausedSock( null, SocketHandle(null, testActor), true )

  			val open = paused.write(ByteString("hello nurse")).restore

  			expectMsgPF(dur) {
  				case IO.Write(_, bytes) =>
  					bytes should be (ByteString("hello nurse"))
  			}
  		}
  	}
}