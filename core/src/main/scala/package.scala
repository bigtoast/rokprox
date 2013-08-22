
package com.github.bigtoast

import akka.actor.{Props, ActorSystem, ActorRef }
import akka.pattern.ask
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import com.ticketfly.pillage._
import scala.concurrent.duration.{Duration, FiniteDuration}
import akka.util.Timeout
import akka.util.Timeout._
import java.util.Random

/**
 * Testing distributed code is hard. Writing tests is inherently difficult and testing
 * for specific types of network failure can be very difficult. Mocking, in my opinion,
 * is not a valid solution as you are not testing against reality. The purpose of this
 * library is to proxy network connections and provide a programmable api to control
 * flow through the proxies.
 *
 * Initially we want to start a proxy that will listen on a port, when a connection
 * to that port is made a connection handler will recieve the connection and open
 * a connection to the target server.
 *
 * There are two types of streams that should be available through the api. We should
 * be able to access a single connection proxy which should not affect other connection
 * proxies. We should also be able to access a stream representing all traffic from our
 * source proxy to the target socket.
 *
 * Initially lets go after just the second type. We can inturrpt all traffic from the source
 * to the target.
 */
package object rokprox {
	import RokProx._
	import RokProxyMessage._

	//implicit val timeout = Timeout( 2 seconds )

	/** adds the build method to the builder only if all fields are set. The build method requires
      * an ActorSystem in implicit scope or passed in explicitly. */
    implicit def addBuild( b :RokProxyBuilder[SET,SET,SET,UNSET] ) = new {

      lazy val container = b._stats.getOrElse( new AsyncStatsContainer( new StatsContainerImpl( new HistogramMetricFactory ) ) )

      def build( implicit system :ActorSystem ) :RokProxy = {

        val props = Props(
          new RokProxyActor(
            sourceHost = b._source.get.split(':')(0),
            sourcePort = b._source.get.split(':')(1).toInt,
            targetHost = b._target.get.split(':')(0),
            targetPort = b._target.get.split(':')(1).toInt,
            stats      = container ) )

        val proxy = system.actorOf( props, name = b._name.get )

        new RokProxy {

          implicit val to = Timeout(5 seconds)

          /** helper method */
          def get[R]( msg :RokProxyMessage, dur :Duration )( implicit m :Manifest[R] ) =
            ask( proxy, msg )( (dur + 1.second).toMillis ).mapTo[R]

          val name = b._name.get

          def break = proxy ! Break

          def interrupt( duration :FiniteDuration ) =
            get[InterruptedStat]( Interrupt( duration ), duration )

          def interrupt =
            proxy ! Interrupt( Duration.Inf )

          def pause( duration :FiniteDuration ) =
            get[PausedStat]( Pause( duration ), duration )

          def pause =
            proxy ! Pause( Duration.Inf )

          def restore = get[ProxyStat]( Restore, to.duration )

          def shutdown = {
            proxy ! Shutdown
            system.stop(proxy)
            if ( container.isInstanceOf[AsyncStatsContainer] )
              container.asInstanceOf[AsyncStatsContainer].shutdown
          }

          def cxn( cxnId :String ) =
            ask( proxy, GetCxn( cxnId ) ).mapTo[CxnStat]

          def cxns =
            ask( proxy, GetAll ).mapTo[Seq[CxnStat]]

		      def interruptCxn( cxnId :String, duration :FiniteDuration ) =
            get[InterruptedCxnStat]( InterruptCxn(cxnId,duration), duration )

          def interruptCxn( cxnId :String ) =
            proxy ! InterruptCxn( cxnId, Duration.Inf )

		      def pauseCxn( cxnId :String, duration :FiniteDuration ) =
            get[PausedCxnStat]( PauseCxn(cxnId,duration), duration )

          def pauseCxn( cxnId :String ) =
            proxy ! PauseCxn(cxnId, Duration.Inf)

		      def breakCxn( cxnId :String ) =
            get[Unit]( BreakCxn(cxnId), to.duration )

		      def restoreCxn( cxnId :String ) =
            get[CxnStat](RestoreCxn(cxnId), to.duration )

        }
      }
    }

    private[rokprox] def create( _name :String, server :ActorRef ) = new RokProxy {

      implicit val to = Timeout(5 seconds)
      implicit val defaultDur = to.duration

    	val name = _name

			def send( msg : RokProxyMessage ) = server ! RokProxyServerMessage.ProxyMessage(name, msg )

			def get[R]( msg :RokProxyMessage, dur :FiniteDuration )( implicit m :Manifest[R] ) =
				ask( server, RokProxyServerMessage.ProxyMessage(name, msg ) )( dur + 1.second ).mapTo[R]

			def break = send( Break )

			def interrupt( duration :FiniteDuration ) =
        get[InterruptedStat]( Interrupt( duration ), duration )

      def interrupt =
        send( Interrupt( Duration.Inf ) )

      def pause( duration :FiniteDuration ) =
        get[PausedStat]( Pause( duration ), duration )

      def pause = send( Pause( Duration.Inf ) )

      def restore = get[ProxyStat]( Restore, defaultDur )

      def shutdown = send( Shutdown )

      def cxn( cxnId :String ) = get[CxnStat]( GetCxn( cxnId ), defaultDur )

      def cxns = get[Seq[CxnStat]]( GetAll, defaultDur)

      def interruptCxn( cxnId :String, duration :FiniteDuration ) =
        get[InterruptedCxnStat]( InterruptCxn(cxnId,duration), duration )

      def interruptCxn( cxnId :String ) =
        send( InterruptCxn( cxnId, Duration.Inf ) )

      def pauseCxn( cxnId :String, duration :FiniteDuration ) =
        get[PausedCxnStat]( PauseCxn(cxnId,duration), duration )

      def pauseCxn( cxnId :String ) =
        send( InterruptCxn( cxnId, Duration.Inf ) )

      def breakCxn( cxnId :String ) = get[Unit]( BreakCxn(cxnId), defaultDur )

      def restoreCxn( cxnId :String ) = get[CxnStat](RestoreCxn(cxnId), defaultDur)

		}

    implicit def addServerBuild( b :RokProxyBuilder[SET,SET,SET,SET] ) = new {
    	def build : RokProxy = {

    		b._server.get ! RokProxyServerMessage.CreateProxy(
    				name   = b._name.get,
    				source = b._source.get,
    				target = b._target.get
    			)

    		create(b._name.get, b._server.get)
    	}
    }

    implicit def addClientBuild( b :RokProxyClientBuilder[SET] ) = new {
    	def build :RokProxyClient = {
    		val port = new Random().nextInt(65535 - 1024) + 1024
    		val config = """
           |akka {
           |  actor {
           |    provider = "akka.remote.RemoteActorRefProvider"
           |  }
           |  remote {
           |    enabled-transports = ["akka.remote.netty.tcp"]
           |    netty.tcp {
           |      hostname = "127.0.0.1"
           |      port = %s
           |    }
           | }
           |}
    		""".stripMargin.format(port)

    		val system = ActorSystem("rokprox", ConfigFactory.parseString(config))

    		new RokProxyClient {
          import scala.concurrent.ExecutionContext.Implicits.global
    			val address = b._address.get

    			def server = system.actorFor("akka.tcp://rokprox@%s/user/rokprox-server" format address)

    			def shutdown = server ! RokProxyServerMessage.Shutdown

    			def exit = system.shutdown

    			def builder = RokProx.builder.server(server)

    			def proxies = ask( server, RokProxyServerMessage.ListProxies )( 5 seconds ).mapTo[Seq[String]].map( _.map( create( _, server) ) )

    			def proxy( name :String ) = create( name, server )

    			def kill( name :String ) = server ! RokProxyServerMessage.KillProxy(name)
    		}
    	}
    }
}
