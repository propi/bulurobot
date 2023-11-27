package bulurobot

import akka.actor.typed.ActorRef
import bulurobot.Main.MainMessage
import bulurobot.Main.MainMessage.{CloseTrading, OpenTrading, Terminate}
import com.typesafe.scalalogging.Logger

import scala.io.StdIn

/**
  * Created by Vaclav Zeman on 28. 12. 2019.
  */
class StdInWatcher private(system: ActorRef[MainMessage]) extends Runnable {

  private val logger = Logger[StdInWatcher]

  @scala.annotation.tailrec
  private def processIn: Boolean = {
    val line = StdIn.readLine()
    line match {
      case "open" =>
        system ! OpenTrading
        processIn
      case "close" =>
        system ! CloseTrading
        processIn
      case "exit" =>
        logger.info(line)
        true
      case null =>
        false
      case _ =>
        logger.info(line)
        processIn
    }
  }

  def run(): Unit = {
    if (processIn) {
      system ! Terminate
    }
  }

}

object StdInWatcher {

  def start(system: ActorRef[MainMessage]): Unit = {
    val thread = new Thread(new StdInWatcher(system))
    thread.setDaemon(true)
    thread.start()
  }

}