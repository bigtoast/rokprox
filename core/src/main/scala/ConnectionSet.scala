package com.github.bigtoast.rokprox

import com.ticketfly.pillage._
import compat.Platform
import akka.actor.IO
import akka.util.ByteString
import collection.mutable
import java.util.UUID


/** Set of bidirectional connections for a single proxy. */
private[rokprox] class ConnectionSet(
    stats      :StatsContainer,
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

  def empty(stats :StatsContainer) = new ConnectionSet(stats = stats)

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

  def apply( proxy :String, source :String, sourceHandle :IO.SocketHandle, target :String, targetHandle :IO.SocketHandle, stats :StatsContainer ) :Connection =
    Connection(
      proxy      = proxy,
      source     = source,
      target     = target,
      upStream   = OpenSock(proxy, targetHandle, sourceHandle, true, stats),
      downStream = OpenSock(proxy, sourceHandle, targetHandle, true, stats),
      stats      = stats )

}

/** A connection has an upstream and a downstream socket. */
private[rokprox] case class Connection(
    proxy       :String,
    source      :String,
    target      :String,
    upStream    :SockProx,
    downStream  :SockProx,
    stats       :StatsContainer,
    id          :String = UUID.randomUUID().toString,
    createdAt   :Long = Platform.currentTime ) {

  val wStat = "rokprox.proxies." + proxy + ".written"
  val bStat = "rokprox.proxies." + proxy + ".buffered"
  val dStat = "rokprox.proxies." + proxy + ".dropped"

  def close = {
    upStream.source.close
    upStream.target.close
  }

  def pause     = copy( upStream = upStream.pause, downStream = downStream.pause )

  def interrupt = copy( upStream = upStream.interrupt, downStream = downStream.interrupt )

  def restore   = copy( upStream = upStream.restore, downStream = downStream.restore )

  def info      = {
    val w = stats.getCounter(wStat).value().toInt
    val b = stats.getCounter(bStat).value().toInt
    val d = stats.getCounter(dStat).value().toInt
    CxnStat( id, proxy, source, target, w, b, d )
  }

}

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

private[rokprox] case class OpenSock( proxy :String, source :IO.SocketHandle, target :IO.SocketHandle, isDownStream :Boolean, stats :StatsContainer ) extends SockProx {

  val stat   = "rokprox.proxies." + proxy + ".written"
  val upDown = "rokprox.proxies." + proxy + (if ( isDownStream ) ".downstream.written" else ".upstream.written")

  def write( bytes :ByteString ) = {
    stats.incr(stat, bytes.length)
    stats.incr(upDown, bytes.length)
    target.write(bytes)
    this
  }

  def pause = PausedSock(proxy, source, target, isDownStream, stats)

  def restore = this

  def interrupt = InterruptedSock(proxy, source, target, isDownStream, stats)
}

private[rokprox] case class InterruptedSock( proxy :String, source :IO.SocketHandle, target :IO.SocketHandle, isDownStream :Boolean, stats :StatsContainer ) extends SockProx {

  val stat   = "rokprox.proxies." + proxy + ".dropped"
  val upDown = "rokprox.proxies." + proxy + (if ( isDownStream ) ".downstream.dropped" else ".upstream.dropped")

  def write( bytes :ByteString ) = {
    stats.incr(stat, bytes.length)
    stats.incr(upDown, bytes.length)
    this
  }

  def interrupt = this

  def pause = PausedSock(proxy, source, target, isDownStream, stats)

  def restore = OpenSock(proxy, source, target, isDownStream, stats)
}

private[rokprox] case class PausedSock( proxy :String, source :IO.SocketHandle, target :IO.SocketHandle, isDownStream :Boolean, stats :StatsContainer, buffered :ByteString = ByteString.empty ) extends SockProx {

  val stat   = "rokprox.proxies." + proxy + ".buffered"
  val upDown = "rokprox.proxies." + proxy + (if ( isDownStream ) ".downstream.buffered" else ".upstream.buffered")

  def interrupt = InterruptedSock(proxy, source, target, isDownStream, stats)

  def write( bytes :ByteString ) = {
    stats.incr(stat, bytes.length)
    stats.incr(upDown, bytes.length)
    copy( buffered = buffered ++ bytes )
  }

  def restore = OpenSock(proxy, source, target, isDownStream, stats).write(buffered)

  def pause = this
}
