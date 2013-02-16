package com.github.bigtoast.rokprox

import akka.actor.{ ActorSystem, IO, ActorRef, Props, IOManager, Actor, FSM }
import akka.util.ByteString
import java.net.InetSocketAddress
import scala.collection.mutable
import java.util.UUID
import scala.util.Try
import scala.concurrent.duration._

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
  * }}}
  */
object RokProx {

  /** create an emtpy builder */
  def builder = new RokProxyBuilder[UNSET,UNSET,UNSET](None,None,None)

  /** create a new builder with the name set. */
  def proxy( name :String ) = builder.name(name)

  private[rokprox] trait SET
  private[rokprox] trait UNSET

  class RokProxyBuilder[N,S,T]( 
    private[rokprox] val _name   :Option[String],
    private[rokprox] val _source :Option[String], 
    private[rokprox] val _target :Option[String] ) {
    import RokProxyMessage._

    /** set the name of the proxy */
    def name( n :String ) = new RokProxyBuilder[SET,S,T](Some(n), _source, _target)

    /** This should be a "host:port" string representing endpoint code using the proxy will
      * connect to. */
    def from( s :String ) = new RokProxyBuilder[N,SET,T](_name, Some(s), _target)

    /** This should be a "host:port" string representing the endpoint the proxy will forward
      * traffic to */
    def to( t :String )   = new RokProxyBuilder[N,S,SET](_name, _source, Some(t))

    /** adds the build method to the builder only if all fields are set. The build method requires
      * an ActorSystem in implicit scope or passed in explicitly. */
    implicit def addBuild( b :RokProxyBuilder[SET,SET,SET] ) = new {
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

          def break = proxy ! Break

          def interrupt( duration :Duration = Duration.Inf ) = proxy ! Interrupt( duration )

          def pause( duration :Duration = Duration.Inf ) = proxy ! Pause( duration )

          def restore = proxy ! Restore

          def shutdown = proxy ! Shutdown
        }
      }
    }
  }

  private[rokprox] sealed trait RokProxyMessage
  private[rokprox] object RokProxyMessage {
    case object Break extends RokProxyMessage
    case class  Interrupt( duration :Duration ) extends RokProxyMessage
    case class  Pause( duration :Duration ) extends RokProxyMessage
    case object Restore extends RokProxyMessage
    case object Shutdown extends RokProxyMessage
  }

  private[this] class ConnectionSet( 
      downStream :mutable.Map[UUID,Connection] = mutable.Map.empty[UUID,Connection], 
      upStream   :mutable.Map[UUID,Connection] = mutable.Map.empty[UUID,Connection] 
    ) extends mutable.Set[Connection] {

    override def contains( conn :Connection ) = downStream.contains(conn.downStream.uuid)

    override def iterator = downStream.valuesIterator
    
    override def +=( conn :Connection ) = {
      downStream + ( conn.downStream.uuid -> conn )
      upStream + ( conn.upStream.uuid -> conn )
      this
    }

    override def -=( conn :Connection ) = {
      downStream - conn.downStream.uuid
      upStream - conn.upStream.uuid
      conn.close
      this
    }

    override def empty = new ConnectionSet

    override def foreach[U]( f :Connection => U ) =
      downStream.foreach { case ( uuid, conn ) => f(conn) }

    override def size = downStream.size

    def socket( uuid :UUID ) :Option[SockProx] = 
      downStream.get(uuid) match { 
        case ds :Some[_] => ds.map(_.downStream) 
        case None => upStream.get( uuid ) map { _.upStream } 
      }

    def connection( uuid :UUID ) :Option[Connection] = 
      downStream.get(uuid) match {
        case conn :Some[_] => conn 
        case None => upStream.get(uuid)
      }

    def -=( uuid :UUID ) :ConnectionSet = connection(uuid).map{ conn => this -= conn } getOrElse this

    def write( uuid :UUID, bytes :ByteString ) = 
      socket(uuid).map( _.write(bytes) )

  }

  private[this] object Connection {
    def apply( source :IO.SocketHandle, target :IO.SocketHandle ) = new Connection {
      val upStream   = SockProx(target, source, false)
      val downStream = SockProx(source, target, true)
      def close = {
        source.close
        target.close
      }
    }
  }

  /** A connection has an upstream and a downstream socket. */
  private[this] sealed trait Connection {
    def upStream   :SockProx
    def downStream :SockProx
    def close
  }

  /** represents a proxied socket. A downStream socket indicates that the target is the service being proxied and
    * the source is the client. An upStream socket indicates that the source is the service being proxied and
    * the target is the client. A client will use a downStream socket and the service will respond on an upStream socket.
    *
    * Writing to a socket always writes to the target. The source is used as the socket identifier.
    */ 
  private[this] case class SockProx( source :IO.SocketHandle, target :IO.SocketHandle, isDownStream :Boolean ) {

    def uuid = source.uuid

    val isUpStream = ! isDownStream

    def write( bytes :ByteString ) = target.write(bytes)

  }

  private[this] sealed trait ProxyState
  private[this] object ProxyState {
    case object INTERRUPTED extends ProxyState
    case object PAUSED extends ProxyState
    case object RUNNING extends ProxyState
  }

  /** All proxying and IO is handled here */
  private[this] class RokProxyActor( 
     sourceHost :String, 
     sourcePort :Int, 
     targetHost :String, 
     targetPort :Int ) extends Actor with FSM[ProxyState,Nothing] {

    import RokProxyMessage._
    import ProxyState._

    var socket :IO.ServerHandle = _

    val connections = new ConnectionSet
    val buffer = mutable.Queue.empty[(UUID,ByteString)]

    override def preStart = {
      socket = IOManager(context.system).listen( new InetSocketAddress( sourceHost, sourcePort ) )
    }

    when(RUNNING) {
      case Event( IO.Read(handle, bytes), _ ) =>
        connections.write(handle.uuid, bytes)
        stay

      case Event( Break, _ ) =>
        connections.foreach { conn => connections -= conn }
        stay

      case Event( Pause( duration ), _ ) =>
        duration match {
          case d :FiniteDuration => 
            setTimer("pause", Restore, d, false)
          case _ =>
        }
          
        goto(PAUSED)

      case Event( Interrupt( duration), _ ) =>
        duration match {
          case d :FiniteDuration => 
            setTimer("interrupt", Restore, d, false)
          case _ =>
        }
          
        goto(PAUSED)
    }

    when(INTERRUPTED) {
      case Event( msg :IO.Read, _ ) =>
        stay

      case Event( Restore, _ ) =>
        cancelTimer("interrupt")
        goto(RUNNING)
    }

    when(PAUSED) {
      case Event( msg :IO.Read, _ ) =>
        buffer.enqueue( ( msg.handle.uuid, msg.bytes ) ) 
        stay

      case Event( Restore, data ) =>
        buffer.dequeueAll( _ => true ).foreach { case ( uuid , bytes ) =>
          connections.write( uuid, bytes )
        }
        cancelTimer("pause")
        goto(RUNNING)
    }

    whenUnhandled {

      case Event( IO.NewClient( serverHandle ), _ ) => 
        val source = serverHandle.accept()
        val target = IOManager(context.system).connect(targetHost, targetPort)
        connections += Connection( source, target )
        stay()

      case Event( msg :IO.Closed, _ ) =>
        connections -= msg.handle.uuid
        stay()

      case Event( Shutdown, _ ) =>
        socket.close
        connections.foreach { _.close }
        stop()

    }

  }

}

/** Handle to a running proxy */
trait RokProxy {

  /** name of the proxy */
  def name :String 

  /** break existing connections.. new connections will succeed */
  def break :Unit

  /** keep connecitons to source and target but interrupt the flow by dropping all bytes */
  def interrupt( duration :Duration = Duration.Inf ) :Unit

  /** keep connections to source and target open but pause the flow by buffering all bytes for this duration. 
    * defaults to INF 
    */
  def pause( duration :Duration = Duration.Inf ) :Unit

  /** restore connection from pauses and interruptions. unpause and flush buffered bytes or start sending
    * bytes again if break was called 
    */
  def restore :Unit

  /** close all connections and shutdown the proxy. this cannot be restarted */
  def shutdown :Unit

}