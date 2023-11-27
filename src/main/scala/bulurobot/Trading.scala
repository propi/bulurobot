package bulurobot

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, ChildFailed, Terminated}
import bulurobot.StockFetcher.StockMessage
import bulurobot.TWSClient.{Action, Event}
import com.ib.client.{Contract, Order}
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by Vaclav Zeman on 11. 10. 2019.
  */
object Trading {

  private val logger = Logger[Trading.type]

  sealed trait TradingType {
    def inversed: TradingType
  }

  object TradingType {

    case object Buy extends TradingType {
      def inversed: TradingType = Sell
    }

    case object Sell extends TradingType {
      def inversed: TradingType = Buy
    }

  }

  sealed trait TradingMessage

  object TradingMessage {

    case object CheckSingleTrading extends TradingMessage

    case object CloseTrading extends TradingMessage

    case class NewSingleTrading(symbol: String, tradingType: TradingType) extends TradingMessage

  }

  sealed trait Status extends TradingMessage

  object Status {

    case class OrderSubmitted(orderId: Int) extends Status

    case class OrderCreated(order: Order, contract: Contract) extends Status

    case class StopOrderCreated(id: Int) extends Status

    case class LimitOrderCreated(id: Int) extends Status

    case object AllIsActive extends Status

  }

  private def resolveStatus(status: Status)(implicit client: TWSClient.Actor, emailLogger: ActorRef[EmailLogger.EmailMessage]): Unit = status match {
    case Status.OrderSubmitted(orderId) => SingleTrade.cancelOrder(orderId)
    case Status.OrderCreated(order, contract) => SingleTrade.cancelOrder(contract, order)
    case Status.StopOrderCreated(orderId) => SingleTrade.cancelOrder(orderId)
    case Status.LimitOrderCreated(orderId) => SingleTrade.cancelOrder(orderId)
    case _ =>
  }

  private def freezed(uncommittedStages: Int)(implicit client: TWSClient.Actor, stockFetcher: ActorRef[StockMessage], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[TradingMessage] = Behaviors.withTimers[TradingMessage] { timer =>
    if (uncommittedStages < 5) {
      timer.startSingleTimer(TradingMessage.CheckSingleTrading, TradingMessage.CheckSingleTrading, 5 minutes)
    } else {
      logger.info("Freezed!")
    }
    Behaviors.receiveMessagePartial[TradingMessage] {
      case TradingMessage.CloseTrading =>
        Behaviors.stopped
      case TradingMessage.CheckSingleTrading =>
        logger.info(s"Recover trading, uncommitedStages = $uncommittedStages")
        newSingleTrading(uncommittedStages)
    }
  }

  private def waitForOtherTrading()(implicit client: TWSClient.Actor, stockFetcher: ActorRef[StockMessage], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[TradingMessage] = Behaviors.withTimers[TradingMessage] { timer =>
    timer.startSingleTimer(TradingMessage.CheckSingleTrading, TradingMessage.CheckSingleTrading, 1 minute)
    Behaviors.receiveMessagePartial[TradingMessage] {
      case TradingMessage.CloseTrading =>
        Behaviors.stopped
      case TradingMessage.CheckSingleTrading =>
        newSingleTrading(0)
    }
  }

  private def status(uncommittedStages: Int, statuses: List[Status] = Nil, closing: Boolean = false)(implicit client: TWSClient.Actor, stockFetcher: ActorRef[StockMessage], singleTrade: ActorRef[Event], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[TradingMessage] = Behaviors.setup[TradingMessage] { context =>
    def resolveTerminatedSingleTrade: Behavior[TradingMessage] = {
      client.tell(Action.UnsubscribeAll(Some(singleTrade), singleTrade))
      if (closing) {
        Behaviors.stopped
      } else {
        waitForOtherTrading()
      }
    }

    Behaviors.receiveMessagePartial[TradingMessage] {
      case x: Status =>
        if (!statuses.headOption.contains(x)) {
          status(uncommittedStages, x :: statuses, closing)
        } else {
          Behaviors.same
        }
      case TradingMessage.CheckSingleTrading =>
        context.cancelReceiveTimeout()
        if (!statuses.headOption.contains(Status.AllIsActive)) {
          context.unwatch(singleTrade)
          context.stop(singleTrade)
          emailLogger ! EmailLogger.EmailMessage.Info(s"Po 10 minutách se nepodařilo vytvořit objednávku. Dosud obdržené statusy jsou: ${statuses.mkString(", ")}. Zkontroluj visící objednávky!!!")
          statuses.foreach(resolveStatus)
          freezed(uncommittedStages + 1)
        } else {
          Behaviors.same
        }
      case TradingMessage.CloseTrading =>
        if (!statuses.headOption.contains(Status.AllIsActive)) {
          client.tell(Action.UnsubscribeAll(Some(singleTrade), singleTrade))
          context.unwatch(singleTrade)
          context.stop(singleTrade)
          statuses.foreach(resolveStatus)
          Behaviors.stopped
        } else {
          singleTrade.tell(Event.CloseTrading)
          status(uncommittedStages, statuses, true)
        }
    }.receiveSignal {
      case (_, ChildFailed(x, th)) =>
        emailLogger ! EmailLogger.EmailMessage.Error(th)
        if (x == singleTrade) resolveTerminatedSingleTrade else Behaviors.same
      case (_, Terminated(x)) if x == singleTrade => resolveTerminatedSingleTrade
    }
  }

  private def newSingleTrading(uncommittedStages: Int)(implicit client: TWSClient.Actor, stockFetcher: ActorRef[StockMessage], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[TradingMessage] = Behaviors.setup[TradingMessage] { context =>
    context.setReceiveTimeout(10 minutes, TradingMessage.CheckSingleTrading)
    logger.info("new single trading request...")
    stockFetcher ! StockFetcher.Get(context.messageAdapter[StockFetcher.FetchedResult] {
      case StockFetcher.FetchedOne(stock, tradingType) => TradingMessage.NewSingleTrading(stock.symbol, tradingType)
      case StockFetcher.FetchedEmpty => TradingMessage.CheckSingleTrading
    })
    Behaviors.receiveMessagePartial[TradingMessage] {
      case TradingMessage.NewSingleTrading(symbol, tradingType) =>
        if (Main.isTimeToStartNewTrading) {
          logger.info(s"new single trading: $symbol")
          implicit val singleTrade: ActorRef[Event] = context.spawn(SingleTrade(symbol, Main.budget, Main.profitThreshold, Main.stopThreshold, tradingType)(client, context.self, emailLogger), "single-trading")
          context.watch(singleTrade)
          context.setReceiveTimeout(10 minutes, TradingMessage.CheckSingleTrading)
          status(uncommittedStages)
        } else {
          logger.info("no time to start new single trading...")
          context.cancelReceiveTimeout()
          Behaviors.same
        }
      case TradingMessage.CloseTrading =>
        Behaviors.stopped
      case TradingMessage.CheckSingleTrading =>
        logger.info(s"no single trading obtained!!!")
        context.cancelReceiveTimeout()
        freezed(uncommittedStages)
    }
  }

  def apply()(implicit client: TWSClient.Actor, emailLogger: ActorRef[EmailLogger.EmailMessage], stockFetcher: ActorRef[StockMessage]): Behavior[TradingMessage] = newSingleTrading(0)

}