package com.github.bigtoast.rokprox

import akka.actor.{ ActorSystem, ActorRef, Props }
import java.util.concurrent.CountDownLatch
import com.typesafe.config.ConfigFactory
import com.ticketfly.pillage._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import java.util

/**
  * Testing fault tolerance in a distributed system is hard. This is a tool to assist in the testing. In
  * a test harness you can start a proxy to a component and control the flow of bytes between your code
  * and the component.
  *
  * {{{
  * val proxy = RokProx.proxy("zk-proxy").from("localhost:8900").to("localhost:8090").build
  *
  * proxy.break                  // break any existing connections
  * proxy.interrupt              // interrupt flow of bytes by dropping them
  * proxy.interrupt( 2 seconds ) // drop bytes for two seconds
  * proxy.restore                // restore stream of bytes..
  * proxy.pause                  // break stream of bytes, while buffering them indefinitely
  * proxy.restore                // restore stream of bytes, flushing all buffered bytes
  * proxy.pause( 10 seconds )    // buffer byte stream for 10 seconds, flushing buffered bytes at end
  *
  * proxy.shutdown // take down the proxy, closing all connections
  *
  * val proxy = RokProx.server("localhost:8900").name("zk-proxy").from("localhost:8999").to("localhost:8090").build
  *
  * val client = RokProx.client("127.0.0.1:2552").build
  *
  * }}}
  */
object RokProx {

  type NewServerProxyBuilder = RokProxyBuilder[UNSET,UNSET,UNSET,SET]

  /** create an emtpy builder */
  def builder = new RokProxyBuilder[UNSET,UNSET,UNSET,UNSET](None,None,None,None,None)

  /** create a new builder with the name set. */
  def proxy( name :String ) = builder.name(name)

  def client( address :String ) = (new RokProxyClientBuilder(None)).address(address)

  private[rokprox] trait SET
  private[rokprox] trait UNSET

  class RokProxyBuilder[N,S,T,C](
    private[rokprox] val _name   :Option[String],
    private[rokprox] val _source :Option[String],
    private[rokprox] val _target :Option[String],
    private[rokprox] val _server :Option[ActorRef],
    private[rokprox] val _stats  :Option[StatsContainer] ) {
    import RokProxyMessage._

    /** set the name of the proxy */
    def name( n :String ) = new RokProxyBuilder[SET,S,T,C](Some(n), _source, _target, _server, _stats)

    /** This should be a "host:port" string representing endpoint code using the proxy will
      * connect to. */
    def from( s :String ) = new RokProxyBuilder[N,SET,T,C](_name, Some(s), _target, _server, _stats)

    /** This should be a "host:port" string representing the endpoint the proxy will forward
      * traffic to */
    def to( t :String )   = new RokProxyBuilder[N,S,SET,C](_name, _source, Some(t), _server, _stats)

    /** add an optional stats container to collect proxy stats. If one is not provided an internal one will be
      * created. This enables use of multiple proxies to share a container */
    def stats( s :StatsContainer ) = new RokProxyBuilder[N,S,T,C](_name, _source, _target, _server, Some(s))

    /** This idicates that the proxy should be creating in a running RokProx server at this address */
    private[rokprox] def server( server :ActorRef ) = new RokProxyBuilder[N,S,T,SET](_name, _source, _target, Some(server), _stats)

    /** Java api builder. If using scala, use the "build" method as it is a typesafe build. This
      * throws an IllegalStateException if a build parameter is missing. */
    def jBuild( system :ActorSystem ) :RokProxy = {
      if ( _name.isEmpty )
        throw new IllegalStateException("Name is not set")

      if ( _source.isEmpty )
        throw new IllegalStateException("Source is not set")

      if ( _target.isEmpty )
        throw new IllegalStateException("Target is not set")

      addBuild( this.asInstanceOf[RokProxyBuilder[SET,SET,SET,UNSET]] ).build(system)
    }

    def jBuild() :RokProxy = {
      if ( _name.isEmpty )
        throw new IllegalStateException("Name is not set")

      if ( _source.isEmpty )
        throw new IllegalStateException("Source is not set")

      if ( _target.isEmpty )
        throw new IllegalStateException("Target is not set")

      if ( _server.isEmpty )
        throw new IllegalStateException("Server is not set")

      addServerBuild( this.asInstanceOf[RokProxyBuilder[SET,SET,SET,SET]] ).build
    }

  }

  class RokProxyClientBuilder[H](
      private[rokprox] val _address :Option[String] ) {

    def address( addy :String ) = new RokProxyClientBuilder[SET]( Some(addy) )

    def jBuild() =
      addClientBuild(this.asInstanceOf[RokProxyClientBuilder[SET]]).build

  }

  def main( args :Array[String] ) = {


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
       |""".stripMargin.format( args.toSeq.headOption.getOrElse(2552) )

    val stats = new AsyncStatsContainer( new StatsContainerImpl( new HistogramMetricFactory ) )

    val sys = ActorSystem("rokprox", ConfigFactory.parseString(config) )

    val latch = new CountDownLatch(1)

    val server = sys.actorOf( Props( new RokProxyServerActor(latch, stats) ), name = "rokprox-server" )

    latch.await

    sys.shutdown

    stats.shutdown

    println("Abschied Freund.")
  }
}

/** public api methods and types */

case class PausedCxnStat( info :CxnStat, duration :Long )

case class InterruptedCxnStat( info :CxnStat, duration :Long )

case class PausedStat( stat :ProxyStat, duration :Long )

case class InterruptedStat( stat :ProxyStat, duration :Long )

case class ProxyStat( name :String, buffered :Int = 0, written :Int = 0, dropped :Int = 0, connections :Seq[String] = Nil )

case class CxnStat( id :String, proxy :String, source :String, target :String, written :Int, buffered :Int, dropped :Int ) {
  /** create an info object of the deltas of two info objects. */
  def delta( info :CxnStat ) =
    copy( written = math.abs(written - info.written), buffered = math.abs(buffered - info.buffered), dropped = math.abs(dropped - info.dropped) )
}

/** Handle to a running proxy */
trait RokProxy {
  import RokProx._

  /** name of the proxy */
  def name :String

  /** break existing connections.. new connections will succeed */
  def break :Unit

  /** keep connecitons to source and target but interrupt the flow by dropping all bytes */
  def interrupt( duration :FiniteDuration ) :Future[InterruptedStat]

  /** drop all bytes indefinitely or until the connections are restored */
  def interrupt :Unit

  /** keep connections to source and target open but pause the flow by buffering all bytes for this duration.
    * defaults to INF
    */
  def pause( duration :FiniteDuration ) : Future[PausedStat]

  /** buffer all connections indifinitely or until restore is called. */
  def pause :Unit

  /** restore connection from pauses and interruptions. unpause and flush buffered bytes or start sending
    * bytes again if break was called
    */
  def restore :Future[ProxyStat]

  /** close all connections and shutdown the proxy. this cannot be restarted */
  def shutdown :Unit

  /** return an existing connection */
  def cxn( cxnId :String ) :Future[CxnStat]

  /** list existing connections in the proxy */
  def cxns :Future[Seq[CxnStat]]

  /** break a single connection */
  def breakCxn( cxnId :String ) :Future[Unit]

  /** interrupt a single connection. The future will return when the connection is restored */
  def interruptCxn( cxnId :String, duration :FiniteDuration ) :Future[InterruptedCxnStat]

  /** interrupt a single connection indefinitely or until the restore is called */
  def interruptCxn( cxnId :String ) :Unit

  /** pause a single connection */
  def pauseCxn( cxnId :String, duration :FiniteDuration ) :Future[PausedCxnStat]

  /** pause a single connection indefinitely, buffering all bytes or until restoreCxn is called */
  def pauseCxn( cxnId :String ) :Unit

  /** restore a connection that is paused or interrupted */
  def restoreCxn( cxnId :String ) :Future[CxnStat]

}

/** client to a running proxy server containing multiple proxies */
trait RokProxyClient {
  import RokProx.NewServerProxyBuilder

  /** shutdown the server */
  def shutdown :Unit

  /** list proxies */
  def proxies :Future[Seq[RokProxy]]

  /** get specific proxy */
  def proxy(name :String) :RokProxy

  /** kill a running proxy */
  def kill(name :String) :Unit

  /** builder for a new proxy */
  def builder :NewServerProxyBuilder

  /** exit the client */
  def exit :Unit

}

