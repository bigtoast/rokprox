
package com.github.bigtoast

import akka.actor.{Props, ActorSystem, ActorRef }
import akka.pattern.ask
import akka.util.{ FiniteDuration, Duration, Timeout }
import akka.util.duration._
import com.typesafe.config.ConfigFactory

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
	import RokProx.{ RokProxyMessage => RPM }

	implicit val timeout = Timeout( 2 seconds )

	/** adds the build method to the builder only if all fields are set. The build method requires
      * an ActorSystem in implicit scope or passed in explicitly. */
    implicit def addBuild( b :RokProxyBuilder[SET,SET,SET,UNSET] ) = new {
      def build( implicit system :ActorSystem ) :RokProxy = {
        val props = Props(
          new RokProxyActor(
            sourceHost = b._source.get.split(':')(0),
            sourcePort = b._source.get.split(':')(1).toInt,
            targetHost = b._target.get.split(':')(0),
            targetPort = b._target.get.split(':')(1).toInt ) )

        val proxy = system.actorOf( props, name = b._name.get )

        new RokProxy {

          val name = b._name.get

          def break = proxy ! RPM.Break

          def interrupt( duration :Duration = Duration.Inf ) = proxy ! RPM.Interrupt( duration )

          def pause( duration :Duration = Duration.Inf ) = proxy ! RPM.Pause( duration )

          def restore = proxy ! RPM.Restore

          def shutdown = proxy ! RPM.Shutdown

          def cxn( cxnId :String ) = ( proxy ask RPM.GetCxn( cxnId ) ).mapTo[CxnInfo]

          def cxns = ( proxy ask RPM.GetAll ).mapTo[Seq[CxnInfo]] 

		      def interruptCxn( cxnId :String, duration :Duration = Duration.Inf ) = proxy ! RPM.InterruptCxn(cxnId,duration)

		      def pauseCxn( cxnId :String, duration :Duration = Duration.Inf ) = proxy ! RPM.PauseCxn(cxnId,duration) 

		      def breakCxn( cxnId :String ) = proxy ! RPM.BreakCxn(cxnId)

		      def restoreCxn( cxnId :String ) = proxy ! RPM.RestoreCxn(cxnId)

        }
      }
    }

    private[rokprox] def create( _name :String, server :ActorRef ) = new RokProxy {

    	val name = _name

			def send( msg : RPM ) = server ! RokProxyServerMessage.ProxyMessage(name, msg )

			def get[R]( msg :RPM )( implicit m :Manifest[R] ) = 
				( server ask RokProxyServerMessage.ProxyMessage(name, msg ) ).mapTo[R]

			def break = send( RPM.Break )

			def interrupt( duration :Duration = Duration.Inf ) = send( RPM.Interrupt( duration ))

      def pause( duration :Duration = Duration.Inf ) = send( RPM.Pause( duration ) )

      def restore = send( RPM.Restore )

      def shutdown = send( RPM.Shutdown )

      def cxn( cxnId :String ) = get[CxnInfo]( RPM.GetCxn( cxnId ) )

      def cxns = get[Seq[CxnInfo]]( RPM.GetAll ) 

      def interruptCxn( cxnId :String, duration :Duration = Duration.Inf ) = send( RPM.InterruptCxn(cxnId,duration))

      def pauseCxn( cxnId :String, duration :Duration = Duration.Inf ) = send( RPM.PauseCxn(cxnId,duration))

      def breakCxn( cxnId :String ) = send( RPM.BreakCxn(cxnId) )

      def restoreCxn( cxnId :String ) = send(RPM.RestoreCxn(cxnId))

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

    implicit def addBuild( b :RokProxyClientBuilder[SET] ) = new {
    	def build :RokProxyClient = {
    		val port = scala.util.Random.nextInt(65535 - 1024) + 1024
    		val config = """
					{
			      akka {
			        actor {
			          provider = "akka.remote.RemoteActorRefProvider"
			        }
			        remote {
			          transport = "akka.remote.netty.NettyRemoteTransport"
			          netty {
			            hostname = "127.0.0.1"
			            port = %d
			          }
			       }
			      }
			    }
    		""".format(port)

    		val system = ActorSystem("rokprox", ConfigFactory.parseString(config))

    		new RokProxyClient {
    			val address = b._address.get

    			def server = system.actorFor("akka://rokprox@%s/user/rokprox-server" format address)

    			def shutdown = server ! RokProxyServerMessage.Shutdown

    			def exit = system.shutdown

    			def builder = RokProx.builder.server(server)

    			def proxies = ( server ask RokProxyServerMessage.ListProxies ).mapTo[Seq[String]].map( _.map( create( _, server) ) )

    			def proxy( name :String ) = create( name, server )

    			def kill( name :String ) = server ! RokProxyServerMessage.KillProxy(name)
    		}
    	}
    }
}