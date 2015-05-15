package konstructs.protocol

import akka.actor.{ Actor,  ActorRef, Props, ActorLogging }
import akka.io._
import akka.util.ByteString

import TcpPipelineHandler.{ Init }

import java.net.InetSocketAddress

class Server(world: ActorRef) extends Actor with ActorLogging {
  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 4080))


  def receive: Receive = {
    case _: Bound =>
      context.become(bound(sender))
    case CommandFailed(_: Bind) => context stop self
  }

  def bound(listener: ActorRef): Receive = {
    case Connected(remote, _) =>
      val init = TcpPipelineHandler.withLogger(log,
        new LengthFieldFrame(maxSize = 256*256*256, headerSize = 4, lengthIncludesHeader = false) >>
          new TcpReadWriteAdapter >>
          new BackpressureBuffer(lowBytes = 100, highBytes = 10000, maxBytes = 16*1024*1024))

      val connection = sender
      val handler = context.actorOf(Client.props(init, world))
      val pipeline = context.actorOf(TcpPipelineHandler.props(
      init, connection, handler))
    connection ! Tcp.Register(pipeline)
  }
}

object Server {
  def props(world: ActorRef) = Props(classOf[Server], world)
}