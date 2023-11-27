package bulurobot

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import bulurobot.EmailLogger.EmailMessage
import bulurobot.Main.MainMessage._
import bulurobot.StockFetcher.StockMessage
import bulurobot.Trading.TradingMessage
import com.typesafe.scalalogging.Logger

import java.time.{LocalTime, ZoneId, ZonedDateTime}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by Vaclav Zeman on 13. 9. 2019.
  */
object Main {

  private val logger = Logger[Main.type]

  val isDemo: Boolean = Conf[Boolean]("bulurobot.demo").value
  val delayed: Boolean = Conf[Boolean]("bulurobot.delayed").value

  val parallelTradings: Int = Conf[Int]("bulurobot.trading.amount").value
  val budget: Int = Conf[Int]("bulurobot.budget").value / parallelTradings
  val stopThreshold: Double = Conf[Double]("bulurobot.trading.stopThreshold").value
  val profitThreshold: Double = Conf[Double]("bulurobot.trading.profitThreshold").value

  val buy: IndexedSeq[String] = Conf[IndexedSeq[String]]("bulurobot.trading.buy").value
  val sell: IndexedSeq[String] = Conf[IndexedSeq[String]]("bulurobot.trading.sell").value

  val openTime: LocalTime = LocalTime.of(9, 30)
  val closeTime: LocalTime = LocalTime.of(16, 0)

  val tradingOpenTime: LocalTime = openTime.plusMinutes(15)
  val tradingCloseTime: LocalTime = closeTime.minusMinutes(15)
  val terminationTime: LocalTime = closeTime.plusMinutes(30)

  sealed trait MainMessage

  object MainMessage {

    final case class ClientObtained(twsClient: EClientWrapper) extends MainMessage

    final case object CloseTrading extends MainMessage

    final case object OpenTrading extends MainMessage

    final case object CheckTradingTime extends MainMessage

    final case object Terminate extends MainMessage

  }

  private def now = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalTime

  private def isTradingTime: Boolean = {
   now.isAfter(tradingOpenTime) && now.isBefore(tradingCloseTime)
  }

  private def isTerminationTime: Boolean = {
    now.isAfter(terminationTime)
  }

  private def handleException(th: Throwable)(implicit emailLogger: ActorRef[EmailLogger.EmailMessage]): Unit = {
    th.printStackTrace()
    emailLogger ! EmailMessage.Error(th)
  }

  def isTimeToStartNewTrading: Boolean = {
    now.isBefore(tradingCloseTime.minusMinutes(15))
  }

  private def closed()(implicit client: TWSClient.Actor, emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[MainMessage] = Behaviors.receivePartial[MainMessage] {
    case (context, OpenTrading) =>
      context.self ! CheckTradingTime
      implicit val stockFetcher: ActorRef[StockMessage] = context.spawn(StockFetcher(), "stock-fetcher")
      val tradings = for (i <- 0 until parallelTradings) yield {
        val trading = context.spawn(Trading(), s"trading-$i")
        context.watch(trading)
        trading
      }
      opened(tradings.toSet)
    case (context, CheckTradingTime) =>
      if (isTradingTime) {
        context.self ! OpenTrading
      } else if (isTerminationTime) {
        context.self ! Terminate
      } else {
        logger.info("Stock exchange is closed.")
        context.scheduleOnce(1 minute, context.self, CheckTradingTime)
      }
      Behaviors.same
    case (_, Terminate) => Behaviors.stopped
  }.receiveSignal {
    case (_, ChildFailed(_, th)) =>
      handleException(th)
      Behaviors.same
  }

  private def opened(tradings: Set[ActorRef[TradingMessage]])(implicit client: TWSClient.Actor, emailLogger: ActorRef[EmailLogger.EmailMessage], stockFetcher: ActorRef[StockMessage]): Behavior[MainMessage] = Behaviors.receivePartial[MainMessage] {
    case (_, CloseTrading) =>
      tradings.foreach(_ ! TradingMessage.CloseTrading)
      Behaviors.same
    case (context, CheckTradingTime) =>
      if (!isTradingTime) {
        context.self ! CloseTrading
      } else {
        logger.info("Stock exchange is open.")
        context.scheduleOnce(1 minute, context.self, CheckTradingTime)
      }
      Behaviors.same
    case (_, Terminate) => Behaviors.stopped
  }.receiveSignal {
    case (context, signal@Terminated(x)) =>
      signal match {
        case ChildFailed(_, th) => handleException(th)
        case _ =>
      }
      val trading = x.unsafeUpcast[TradingMessage]
      if (tradings(trading)) {
        val removedTradings = tradings - trading
        if (removedTradings.isEmpty) {
          context.stop(stockFetcher)
          context.self ! CheckTradingTime
          closed()
        } else {
          opened(removedTradings)
        }
      } else {
        Behaviors.same
      }
  }

  def main(args: Array[String]): Unit = {
    val system = ActorSystem(Behaviors.setup[MainMessage] { context =>
      implicit val emailLogger: ActorRef[EmailLogger.EmailMessage] = context.spawn(EmailLogger(), "email-logger")
      val client = context.spawn(TWSClient(), "tws-client")
      context.watch(client)
      client ! TWSClient.Action.GetClient(context.self)
      Behaviors.receiveMessagePartial[MainMessage] {
        case ClientObtained(twsClient) =>
          logger.info("TWS client obtained")
          context.self ! CheckTradingTime
          closed()(TWSClient.Actor(client, twsClient), emailLogger)
        case Terminate => Behaviors.stopped
      }.receiveSignal {
        case (_, ChildFailed(_, th)) =>
          handleException(th)
          Behaviors.stopped
      }
    }, "bulurobot")
    StdInWatcher.start(system)
    Await.result(system.whenTerminated, Duration.Inf)
    println("UKONCUJI BULUROBOTA.")
  }

}