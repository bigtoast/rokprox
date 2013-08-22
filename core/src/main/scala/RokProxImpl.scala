package com.github.bigtoast.rokprox

import akka.actor._
import scala.concurrent.duration._
import com.ticketfly.pillage.StatsContainer
import java.util.concurrent.CountDownLatch
import collection.immutable.Queue
import scala.compat.Platform
import java.net.InetSocketAddress
import java.util.UUID
import akka.util.ByteString


private[rokprox] sealed trait RokProxyMessage
private[rokprox] object RokProxyMessage {

  /** proxy specific commands */
  case class Interrupt( duration :Duration )  extends RokProxyMessage
  case class Pause( duration :Duration )      extends RokProxyMessage
  case object Break     extends RokProxyMessage
  case object Restore   extends RokProxyMessage
  case object ScheduledRestore extends RokProxyMessage
  case object Shutdown  extends RokProxyMessage
  case object GetAll    extends RokProxyMessage

  /** connection specific commands */
  case class GetCxn( cxnId :String )    extends RokProxyMessage
  case class PauseCxn(cxnId :String, duration :Duration )     extends RokProxyMessage
  case class InterruptCxn(cxnId :String, duration :Duration ) extends RokProxyMessage
  case class ScheduledRestoreCxn( cxnId :String ) extends RokProxyMessage
  case class RestoreCxn(cxnId :String) extends RokProxyMessage
  case class BreakCxn(cxnId :String )  extends RokProxyMessage

}

private[rokprox] sealed trait ProxyState
private[rokprox] object ProxyState {
  case object INTERRUPTED extends ProxyState
  case object PAUSED      extends ProxyState
  case object RUNNING     extends ProxyState
}

private[rokprox] sealed trait ProxyData
private[rokprox] object ProxyData {
  case class RunningData() extends ProxyData
  case class PausedData( respondTo: ActorRef, buffered :Queue[(UUID,ByteString)] = Queue.empty, pausedAt :Long = Platform.currentTime ) extends ProxyData
  case class InterruptedData( respondTo :ActorRef, dropped :Int = 0, interruptedAt :Long = Platform.currentTime) extends ProxyData
}

/** All proxying and IO is handled here */
private[rokprox] class RokProxyActor(
    sourceHost :String,
    sourcePort :Int,
    targetHost :String,
    targetPort :Int,
    stats      :StatsContainer ) extends Actor with LoggingFSM[ProxyState,ProxyData] {

  import RokProxyMessage._
  import ProxyState._
  import ProxyData._

  var socket :IO.ServerHandle = _

  val connections      = new ConnectionSet(stats)
  var waitingOnRestore = Map.empty[String,(CxnStat,ActorRef,RokProxyMessage,Long)]

  lazy val name = context.self.path.name

  val written  = "rokprox." + name + ".written"
  val buffered = "rokprox." + name + ".buffered"
  val dropped  = "rokprox." + name + ".dropped"

  override def preStart = {
    socket = IOManager(context.system).listen( new InetSocketAddress( sourceHost, sourcePort ) )
  }

  startWith(RUNNING,RunningData())

  when(RUNNING) {
    case Event( IO.Read(handle, bytes), data :RunningData ) =>
      connections.write(handle.uuid, bytes)
      stay

    case Event( Break, data :RunningData ) =>
      connections.foreach { conn => connections -= conn }
      stay

    case Event( Pause( duration ), data :RunningData ) =>
      duration match {
        case d :FiniteDuration =>
          setTimer("pause", ScheduledRestore, d, false)
        case _ =>
      }

      goto(PAUSED) using(PausedData(context.sender))

    case Event( Interrupt( duration), data :RunningData ) =>
      duration match {
        case d :FiniteDuration =>
          setTimer( "interrupt", ScheduledRestore, d, false )
        case _ =>
      }

      goto(INTERRUPTED) using InterruptedData(context.sender)
  }

  when(INTERRUPTED) {
    case Event( msg :IO.Read, data :InterruptedData ) =>
      stats.incr(dropped, msg.bytes.length)
      stay using data.copy( dropped = data.dropped + msg.bytes.length )

    case Event( Restore, data :InterruptedData ) =>
      cancelTimer("interrupt")
      val stat = ProxyStat(name, dropped = data.dropped)
      data.respondTo ! InterruptedStat( stat, Platform.currentTime - data.interruptedAt )
      context.sender ! stat
      goto(RUNNING) using RunningData()

    case Event( ScheduledRestore, data :InterruptedData ) =>
      cancelTimer("interrupt")
      data.respondTo ! InterruptedStat( ProxyStat(name, dropped = data.dropped), Platform.currentTime - data.interruptedAt )
      goto(RUNNING) using RunningData()
  }

  when(PAUSED) {
    case Event( msg :IO.Read, data :PausedData ) =>
      stats.incr(buffered, msg.bytes.length)
      stay using data.copy( buffered = data.buffered.enqueue( ( msg.handle.uuid, msg.bytes ) ) )

    case Event( Restore, data :PausedData ) =>
      var cnt = 0
      data.buffered.foreach { case ( uuid, bytes) =>
        connections.write( uuid, bytes )
        cnt = cnt + 1
      }
      cancelTimer("pause")
      stats.add(written, cnt)
      val stat = ProxyStat(name, buffered = cnt)
      data.respondTo ! PausedStat( stat, Platform.currentTime - data.pausedAt )
      context.sender ! stat
      goto(RUNNING)

    case Event( ScheduledRestore, data :PausedData ) =>
      var cnt = 0
      data.buffered.foreach { case ( uuid, bytes) =>
        connections.write( uuid, bytes )
        cnt = cnt + 1
      }
      cancelTimer("pause")
      stats.add(written, cnt)
      data.respondTo ! PausedStat( ProxyStat(name, buffered = cnt ), Platform.currentTime - data.pausedAt )
      goto(RUNNING)
  }

  whenUnhandled {

    case Event( IO.NewClient( serverHandle ), _ ) =>
      val source = serverHandle.accept()
      val target = IOManager(context.system).connect(targetHost, targetPort)
      stats.incr("rokprox.proxies." + name + ".connections")
      connections += Connection( context.self.path.name, sourceHost + ":" + sourcePort, source, targetHost + ":" + targetPort, target, stats )
      stay()

    case Event( msg :IO.Closed, _ ) =>
      stats.incr("rokprox.proxies." + name + ".connections", -1)
      connections -= msg.handle.uuid
      stay()

    case Event( c @ IO.Connected( handle, addy ), _ ) =>
      stay()

    case Event( m :IO.Listening, _ ) =>
      stay()

    case Event( p @ PauseCxn( cxnId, duration ), _ ) =>
      connections.update(cxnId){ cxn =>
        waitingOnRestore = waitingOnRestore + ( cxnId -> (cxn.info, context.sender, p, Platform.currentTime) )
        cxn.pause
      }
      if (duration.isFinite())
        setTimer("restore-" + cxnId, ScheduledRestoreCxn(cxnId), FiniteDuration(duration._1, duration._2), false )
      stay

    case Event( i @ InterruptCxn( cxnId, duration ), _ ) =>
      connections.update(cxnId){ cxn =>
        waitingOnRestore = waitingOnRestore + ( cxnId -> (cxn.info, context.sender, i, Platform.currentTime ) )
        cxn.interrupt
      }
      if (duration.isFinite())
        setTimer("restore-" + cxnId, ScheduledRestoreCxn(cxnId), FiniteDuration(duration._1, duration._2), false )
      stay

    case Event( ScheduledRestoreCxn( cxnId ), _ ) =>
      connections.update(cxnId){ cxn =>
        waitingOnRestore.get(cxnId) foreach {
          case ( info, aRef, p :PauseCxn, pausedAt ) =>
            aRef ! PausedCxnStat( info.delta(cxn.info), Platform.currentTime - pausedAt )

          case ( info, aRef, i :InterruptCxn, interruptedAt ) =>
            aRef ! InterruptedCxnStat( info.delta(cxn.info), Platform.currentTime - interruptedAt )

          case _ =>
        }
        waitingOnRestore = waitingOnRestore - cxnId
        cxn.restore
      }
      cancelTimer("restore-" + cxnId)
      stay

    case Event( RestoreCxn( cxnId ), _ ) =>
      connections.update(cxnId){ cxn =>
        waitingOnRestore.get(cxnId) map {
          case ( info, aRef, p :PauseCxn, pausedAt ) =>
            val delta = info.delta(cxn.info)
            aRef ! PausedCxnStat( delta, Platform.currentTime - pausedAt )
            context.sender ! delta

          case ( info, aRef, p :InterruptCxn, interruptedAt ) =>
            val delta = info.delta(cxn.info)
            aRef ! InterruptedCxnStat( delta, Platform.currentTime - interruptedAt )
            context.sender ! delta

          case _ =>

        } getOrElse {
          context.sender ! Status.Failure(new IllegalStateException("Connection %s not paused or interrupted" format cxnId))
        }
        waitingOnRestore = waitingOnRestore - cxnId
        cxn.restore
      }
      cancelTimer("restore-" + cxnId)
      stay

    case Event( GetCxn( cxnId ), _ ) =>
      connections.find(_.id == cxnId) match {
        case Some( cxn ) =>
          stay replying cxn.info
        case None =>
          stay replying Status.Failure(new NoSuchElementException("No connection for id %s" format cxnId))
      }

    case Event( BreakCxn( cxnId ), _ ) =>
      connections.find(_.id == cxnId) foreach( connections -= )
      stay replying ()

    case Event( GetAll, _ ) =>
      stay replying connections.map(_.info).toSeq

    case Event( Shutdown, _ ) =>
      socket.close
      connections.foreach { _.close }
      stop()

    case Event( e, _ ) =>
      log.info("Received unhandled {} in proxy {}", e, name)
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

private[rokprox] class RokProxyServerActor( stopLatch :CountDownLatch, stats :StatsContainer ) extends Actor with ActorLogging {

  import RokProxyServerMessage._

  var proxies = Map.empty[String,RokProxy]

  override def postStop = stopLatch.countDown

  def receive = {
    case CreateProxy(name, source, target) =>
      log.debug("Creating proxy: {}, from: {} to:{}", name, source, target)
      stats.incr("rokprox.proxies")
      val prox = RokProx.proxy(name).from(source).to(target).build(context.system)
      proxies = proxies + ( name -> prox )

    case KillProxy(name) =>
      stats.incr("rokprox.proxies", -1)
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


