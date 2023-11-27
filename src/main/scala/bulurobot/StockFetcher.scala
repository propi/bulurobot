package bulurobot

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import bulurobot.Trading.TradingType

import scala.util.Random

/**
  * Created by Vaclav Zeman on 1. 11. 2019.
  */
object StockFetcher {

  case class Stock(symbol: String)

  sealed trait StockMessage

  final case class Get(sender: ActorRef[FetchedResult]) extends StockMessage

  sealed trait FetchedResult

  final case class FetchedOne(stock: Stock, tradingType: Trading.TradingType) extends FetchedResult

  final case object FetchedEmpty extends FetchedResult

  private def waiting(stockList: List[(TradingType, Stock)]): Behavior[StockMessage] = Behaviors.receiveMessagePartial {
    case Get(sender) =>
      stockList match {
        case x :: tail =>
          sender ! FetchedOne(x._2, x._1)
          waiting(tail)
        case Nil =>
          sender ! FetchedEmpty
          Behaviors.same
      }
  }

  def apply(): Behavior[StockMessage] = {
    val stockList = Random.shuffle(Main.buy.iterator.map(x => TradingType.Buy -> Stock(x)) ++ Main.sell.iterator.map(x => TradingType.Sell -> Stock(x)))(List)
    Behaviors.supervise(waiting(stockList)).onFailure(SupervisorStrategy.resume)
  }

}