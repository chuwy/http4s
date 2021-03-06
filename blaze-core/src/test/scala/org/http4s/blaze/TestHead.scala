package org.http4s.blaze

import org.http4s.blaze.pipeline.HeadStage
import org.http4s.blaze.pipeline.Command.{Disconnected, Disconnect, OutboundCommand, EOF}

import java.nio.ByteBuffer

import scala.concurrent.duration.Duration
import scala.concurrent.{ Promise, Future }
import scala.util.{Success, Failure, Try}


abstract class TestHead(val name: String) extends HeadStage[ByteBuffer] {

  private var acc = Vector[Array[Byte]]()
  private val p = Promise[ByteBuffer]

  var closed = false

  def getBytes(): Array[Byte] = acc.toArray.flatten

  def result = p.future

  override def writeRequest(data: ByteBuffer): Future[Unit] = synchronized {
    if (closed) Future.failed(EOF)
    else {
      val cpy = new Array[Byte](data.remaining())
      data.get(cpy)
      acc :+= cpy
      Future.successful(())
    }
  }

  override def stageShutdown(): Unit = synchronized {
    closed = true
    super.stageShutdown()
    p.trySuccess(ByteBuffer.wrap(getBytes()))
    ()
  }
}

class SeqTestHead(body: Seq[ByteBuffer]) extends TestHead("SeqTestHead") {
  private val bodyIt = body.iterator

  override def readRequest(size: Int): Future[ByteBuffer] = synchronized {
    if (!closed && bodyIt.hasNext) Future.successful(bodyIt.next())
    else {
      stageShutdown()
      sendInboundCommand(Disconnected)
      Future.failed(EOF)
    }
  }
}

final class SlowTestHead(body: Seq[ByteBuffer], pause: Duration) extends TestHead("Slow TestHead") { self =>
  import org.http4s.blaze.util.Execution.scheduler

  private val bodyIt = body.iterator
  private var currentRequest: Option[Promise[ByteBuffer]] = None

  private def resolvePending(result: Try[ByteBuffer]): Unit = {
    currentRequest.foreach(_.tryComplete(result))
    currentRequest = None
  }

  private def clear(): Unit = synchronized {
    while(bodyIt.hasNext) bodyIt.next()
    resolvePending(Failure(EOF))
  }

  override def stageShutdown(): Unit = synchronized {
    clear()
    super.stageShutdown()
  }

  override def outboundCommand(cmd: OutboundCommand): Unit = self.synchronized {
    cmd match {
      case Disconnect => clear()
      case _          => sys.error(s"TestHead received weird command: $cmd")
    }
    super.outboundCommand(cmd)
  }

  override def readRequest(size: Int): Future[ByteBuffer] = self.synchronized {
    currentRequest match {
      case Some(_) => Future.failed(new IllegalStateException("Cannot serve multiple concurrent read requests"))
      case None =>
        val p = Promise[ByteBuffer]
        currentRequest = Some(p)

        scheduler.schedule(new Runnable {
          override def run(): Unit = self.synchronized {
            resolvePending {
              if (!closed && bodyIt.hasNext) Success(bodyIt.next())
              else Failure(EOF)
            }
          }
        }, pause)

        p.future
    }
  }
}
