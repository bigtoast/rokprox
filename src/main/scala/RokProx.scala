package com.github.bigtoast.rokprox

import akka.actor.{ ActorSystem, IO, ActorRef, Props, IOManager, Actor, FSM, LoggingFSM, ActorLogging, Status }
import akka.util.ByteString
import akka.dispatch.Future
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import scala.collection.mutable
import com.eaio.uuid.UUID
import akka.util.{ FiniteDuration, Duration }
import akka.util.duration._
import compat.Platform
import com.typesafe.config.ConfigFactory

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
  def builder = new RokProxyBuilder[UNSET,UNSET,UNSET,UNSET](None,None,None,None)

  /** create a new builder with the name set. */
  def proxy( name :String ) = builder.name(name)

  def client( address :String ) = (new RokProxyClientBuilder(None)).address(address)

  private[rokprox] trait SET
  private[rokprox] trait UNSET

  class RokProxyBuilder[N,S,T,C]( 
    private[rokprox] val _name   :Option[String],
    private[rokprox] val _source :Option[String], 
    private[rokprox] val _target :Option[String],
    private[rokprox] val _server :Option[ActorRef]) {
    import RokProxyMessage._

    /** set the name of the proxy */
    def name( n :String ) = new RokProxyBuilder[SET,S,T,C](Some(n), _source, _target, _server)

    /** This should be a "host:port" string representing endpoint code using the proxy will
      * connect to. */
    def from( s :String ) = new RokProxyBuilder[N,SET,T,C](_name, Some(s), _target, _server)

    /** This should be a "host:port" string representing the endpoint the proxy will forward
      * traffic to */
    def to( t :String )   = new RokProxyBuilder[N,S,SET,C](_name, _source, Some(t), _server)

    /** This idicates that the proxy should be creating in a running RokProx server at this address */
    private[rokprox] def server( server :ActorRef ) = new RokProxyBuilder[N,S,T,SET](_name, _source, _target, Some(server))

  }

  class RokProxyClientBuilder[H](
      private[rokprox] val _address :Option[String] ) {

    def address( addy :String ) = new RokProxyClientBuilder[SET]( Some(addy) )

  }

  private[rokprox] sealed trait RokProxyMessage
  private[rokprox] object RokProxyMessage {

    /** proxy specific commands */
    case class Interrupt( duration :Duration )  extends RokProxyMessage
    case class Pause( duration :Duration )      extends RokProxyMessage
    case object Break     extends RokProxyMessage
    case object Restore   extends RokProxyMessage
    case object Shutdown  extends RokProxyMessage
    case object GetAll    extends RokProxyMessage

    /** connection specific commands */
    case class GetCxn( cxnId :String )    extends RokProxyMessage
    case class PauseCxn(cxnId :String, duration :Duration ) extends RokProxyMessage
    case class InterruptCxn(cxnId :String, duration :Duration ) extends RokProxyMessage
    case class RestoreCxn(cxnId :String ) extends RokProxyMessage
    case class BreakCxn(cxnId :String )   extends RokProxyMessage

  }

  /** Set of bidirectional connections for a single proxy. */
  private[rokprox] class ConnectionSet( 
      downStream :mutable.Map[UUID,Connection] = mutable.Map.empty[UUID,Connection], 
      upStream   :mutable.Map[UUID,Connection] = mutable.Map.empty[UUID,Connection] 
    ) extends mutable.Set[Connection] {

    override def contains( conn :Connection ) = downStream.contains(conn.downStream.uuid)

    override def iterator = downStream.valuesIterator
    
    override def +=( conn :Connection ) = {
      downStream += ( conn.downStream.uuid -> conn )
      upStream += ( conn.upStream.uuid -> conn )
      this
    }

    override def -=( conn :Connection ) = {
      downStream -= conn.downStream.uuid
      upStream -= conn.upStream.uuid
      conn.close
      this
    }

    override def empty = new ConnectionSet

    override def foreach[U]( f :Connection => U ) =
      downStream.foreach { case ( uuid, conn ) => f(conn) }

    override def size = downStream.size

    def socket( uuid :UUID ) :Option[SockProx] =
      downStream.get(uuid) match { 
        case ds :Some[_] => 
          ds.map(_.downStream) 
        case None => 
          upStream.get( uuid ) map { _.upStream } 
      }

    def connection( uuid :UUID ) :Option[Connection] = 
      downStream.get(uuid) match {
        case conn :Some[_] => conn 
        case None => upStream.get(uuid)
      }

    def -=( uuid :UUID ) :ConnectionSet = connection(uuid).map { conn => this -= conn } getOrElse this

    def write( uuid :UUID, bytes :ByteString ) = socket(uuid).map( _.write(bytes) )

    def update( cxnId :String )( f :Connection => Connection ) = 
      find( _.id == cxnId ) map( f ) foreach( += ) 

  }

  private[rokprox] object Connection {

    def apply( proxy :String, source :String, sourceHandle :IO.SocketHandle, target :String, targetHandle :IO.SocketHandle ) :Connection = 
      Connection(
        proxy      = proxy,
        source     = source,
        target     = target,
        upStream   = OpenSock(targetHandle, sourceHandle, false),
        downStream = OpenSock(sourceHandle, targetHandle, true) )

  }

  /** A connection has an upstream and a downstream socket. */
  private[rokprox] case class Connection(
      proxy       :String,
      source      :String,
      target      :String,
      upStream    :SockProx,
      downStream  :SockProx,
      id          :String = (new com.eaio.uuid.UUID ) toString,
      createdAt   :Long = Platform.currentTime ) {

    def close = {
      upStream.source.close
      upStream.target.close
    }

    def pause = copy( upStream = upStream.pause, downStream = downStream.pause )

    def interrupt = copy( upStream = upStream.interrupt, downStream = downStream.interrupt )

    def restore = copy( upStream = upStream.restore, downStream = downStream.restore ) 

    def info = CxnInfo( id, proxy, source, target, createdAt )
  
  }

  case class CxnInfo( id :String, proxy :String, source :String, target :String, createdAt :Long )

  /** represents a proxied socket. A downStream socket indicates that the target is the service being proxied and
    * the source is the client. An upStream socket indicates that the source is the service being proxied and
    * the target is the client. A client will use a downStream socket and the service will respond on an upStream socket.
    *
    * Writing to a socket always writes to the target. The source is used as the socket identifier.
    */ 
  private[rokprox] sealed trait SockProx {
    
    def source :IO.SocketHandle

    def target :IO.SocketHandle

    def isDownStream :Boolean

    def uuid = source.uuid

    val isUpStream = ! isDownStream

    def write( bytes :ByteString ) :SockProx 

    def interrupt :SockProx

    def pause :SockProx

    def restore :SockProx

  }

  private[rokprox] case class OpenSock( source :IO.SocketHandle, target :IO.SocketHandle, isDownStream :Boolean ) extends SockProx {
    def write( bytes :ByteString ) = { target.write(bytes); this }

    def pause = PausedSock(source,target,isDownStream)

    def restore = this

    def interrupt = InterruptedSock(source,target,isDownStream)
  }

  private[rokprox] case class InterruptedSock( source :IO.SocketHandle, target :IO.SocketHandle, isDownStream :Boolean ) extends SockProx {
    def write( bytes :ByteString ) = this

    def interrupt = this

    def pause = PausedSock(source,target,isDownStream)

    def restore = OpenSock(source,target,isDownStream)
  }

  private[rokprox] case class PausedSock( source :IO.SocketHandle, target :IO.SocketHandle, isDownStream :Boolean, buffered :ByteString = ByteString.empty ) extends SockProx {
    def interrupt = InterruptedSock(source, target, isDownStream)

    def write( bytes :ByteString ) = copy( buffered = buffered ++ bytes )

    def restore = OpenSock(source,target,isDownStream).write(buffered)

    def pause = this
  }

  private[rokprox] sealed trait ProxyState
  private[rokprox] object ProxyState {
    case object INTERRUPTED extends ProxyState
    case object PAUSED      extends ProxyState
    case object RUNNING     extends ProxyState
  }

  /** All proxying and IO is handled here */
  private[rokprox] class RokProxyActor( 
     sourceHost :String, 
     sourcePort :Int, 
     targetHost :String, 
     targetPort :Int ) extends Actor with LoggingFSM[ProxyState,Any] {

    import RokProxyMessage._
    import ProxyState._

    var socket :IO.ServerHandle = _

    val connections = new ConnectionSet
    val buffer = mutable.Queue.empty[(UUID,ByteString)]

    lazy val name = context.self.path.name

    override def preStart = {
      socket = IOManager(context.system).listen( new InetSocketAddress( sourceHost, sourcePort ) )
    }

    startWith(RUNNING,1)

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
          
        goto(INTERRUPTED)
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
        connections += Connection( context.self.path.name, sourceHost + ":" + sourcePort, source, targetHost + ":" + targetPort, target )
        stay()

      case Event( msg :IO.Closed, _ ) =>
        connections -= msg.handle.uuid
        stay()

      case Event( c @ IO.Connected( handle, addy ), _) =>
        stay()

      case Event( PauseCxn( cxnId, duration ), _ ) =>
        connections.update(cxnId){ _.pause }
        setTimer("restore-" + cxnId, RestoreCxn(cxnId), duration, false )
        stay

      case Event( RestoreCxn( cxnId ), _ ) =>
        connections.update(cxnId){ _.restore }
        cancelTimer("restore-" + cxnId)
        stay

      case Event( InterruptCxn( cxnId, duration ), _ ) =>
        connections.update(cxnId){ _.interrupt }
        setTimer("restore-" + cxnId, RestoreCxn(cxnId), duration, false )
        stay

      case Event( GetCxn( cxnId ), _ ) =>
        connections.find(_.id == cxnId) match {
          case Some( cxn ) => 
            stay replying cxn
          case None =>
            stay replying Status.Failure(new NoSuchElementException("No connection for id %s" format cxnId))
        }

      case Event( BreakCxn( cxnId ), _ ) =>
        connections.find(_.id == cxnId) foreach( connections -= )
        stay()

      case Event( GetAll, _ ) =>
        stay replying connections.map(_.info).toSeq

      case Event( Shutdown, _ ) =>
        socket.close
        connections.foreach { _.close }
        stop()

      case Event( e, _ ) =>
        log.error("Received unhandled {} in proxy {}", e, name)
        stay()

    }

    onTransition {
      case from -> to =>
        log.info("Transition proxy {} state {} -> {}", name, from, to)
    }

    initialize
  }

  private[rokprox] sealed trait RokProxyServerMessage 
  private[rokprox] object RokProxyServerMessage {
    case class CreateProxy( name :String, source :String, target :String ) extends RokProxyServerMessage
    case class KillProxy( name :String ) extends RokProxyServerMessage
    case object ListProxies extends RokProxyServerMessage
    case class ProxyMessage( name: String, msg :RokProxyMessage ) extends RokProxyServerMessage
    case object Shutdown extends RokProxyServerMessage
  }

  private[rokprox] class RokProxyServerActor( stopLatch :CountDownLatch ) extends Actor with ActorLogging {

    import RokProxyServerMessage._

    var proxies = Map.empty[String,RokProxy]

    override def postStop = stopLatch.countDown

    def receive = {
      case CreateProxy(name, source, target) =>
        log.debug("Creating proxy: {}, from: {} to:{}", name, source, target)
        val prox = RokProx.proxy(name).from(source).to(target).build(context.system)
        proxies = proxies + ( name -> prox )

      case KillProxy(name) =>
        proxies.get(name) foreach { _.shutdown }
        proxies = proxies - name

      case ListProxies =>
        sender ! proxies.map(_._1)

      case ProxyMessage(name, msg) =>
        context.system.actorFor("/user/" + name ) forward msg

      case Shutdown =>
        context.stop(self)

      case msg => 
        log.error("Received unhandled messsage {}", msg)
    }

  }

  def main( args :Array[String] ) = {

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
            port = %s
          }
       }
      }
    }
    """.format( args.toSeq.headOption.getOrElse(2552) )

    val sys = ActorSystem("rokprox", ConfigFactory.parseString(config))

    val latch = new CountDownLatch(1)

    val server = sys.actorOf(Props( new RokProxyServerActor(latch) ), name = "rokprox-server" )

    latch.await

    sys.shutdown

    println("Abschied Freund.")
  } 

}

/** Handle to a running proxy */
trait RokProxy {
  import RokProx._

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

  /** return an existing connection */
  def cxn( cxnId :String ) :Future[CxnInfo] 

  /** list existing connections in the proxy */
  def cxns :Future[Seq[CxnInfo]] 

  /** break a single connection */
  def breakCxn( cxnId :String ) :Unit 

  /** interrupt a single connection */
  def interruptCxn( cxnId :String, duration :Duration = Duration.Inf ) :Unit

  /** pause a single connection */
  def pauseCxn( cxnId :String, duration :Duration = Duration.Inf ) :Unit 

  /** restore a connection that is paused or interrupted */
  def restoreCxn( cxnId :String ) :Unit 

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

  def kill(name :String) :Unit

  /** builder for a new proxy */
  def builder :NewServerProxyBuilder

  /** exit the client */ 
  def exit :Unit

}