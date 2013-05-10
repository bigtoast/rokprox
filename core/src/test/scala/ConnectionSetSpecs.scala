package com.github.bigtoast.rokprox

import org.scalatest._
import org.scalatest.matchers.ShouldMatchers
import com.ticketfly.pillage._

import RokProx._

import akka.testkit.{ TestActorRef, TestKit }
import akka.actor.{ ActorRef, IO, Actor, ActorSystem, Props, IOManager }
import akka.util.ByteString
import IO._
import java.net.InetSocketAddress

class ConnectionSetSpecs extends WordSpec with ShouldMatchers with BeforeAndAfterAll{

	implicit val system = ActorSystem("testers")

	override def afterAll {
    	system.shutdown()
  	}

  	"Connect to diva" in {
  		val act = Props( new Actor {

  				override def preStart = IOManager(context.system).connect(new InetSocketAddress("localhost",8999))

  				def receive = {
  					case IO.Connected( handle, addy ) =>
  						handle.write(ByteString("jibidy"))

  					case IO.Read(handle, bytes) =>
  						//println("I read %s from handle %s"format(bytes,handle))
  						//handle.write(ByteString("crapfactory"))

  					case msg =>
  				}
  			})

  		val ref = system.actorOf(act)
  		ref ! "craps"
  		Thread.sleep(5000)
  	}

	"A connection set" should {
		"accept a new connection" in {
      val stats = new StatsContainerImpl( new HistogramMetricFactory ) 
			val dummyRef = TestActorRef( new Actor { def receive = { case _ => } } )
			val set = new ConnectionSet( stats )
			val source = SocketHandle(dummyRef, dummyRef)
			val target = SocketHandle(dummyRef,dummyRef)

			source.uuid should not be (target.uuid)
			
			val conn = Connection("prox", "sourceHost", source, "targetHost", target, stats)

			set should have size 0

			set += conn

			set should have size 1
		}

		"remove a connection" is (pending)
	} 
}