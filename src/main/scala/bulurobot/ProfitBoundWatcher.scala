package bulurobot

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal}
import bulurobot.TWSClient.{Action, Event, EventType}
import com.ib.client.{Contract, Order, Types}
import com.typesafe.scalalogging.Logger

object ProfitBoundWatcher {

  private val logger = Logger[ProfitBoundWatcher.type]

  val activated: Boolean = Conf[Boolean]("bulurobot.trading.profitBoundsActivated").value
  private val lowerBound: Double = Conf[Double]("bulurobot.trading.profitLowerBoundThreshold").value
  private val upperBound: Double = Conf[Double]("bulurobot.trading.profitUpperBoundThreshold").value

  case class ReplyCommand(currectPrice: Double, lowerBoundPrice: Double)

  sealed trait Command

  private case object Subscribed extends Command

  private case object UnhandledEvent extends Command

  final private case class ContractPrice(price: Double) extends Command

  final private case class BoundPrice(price: Double) {
    def >=(currentPrice: Double)(implicit order: Order): Boolean = if (order.action() == Types.Action.BUY) {
      price >= currentPrice
    } else {
      price <= currentPrice
    }

    def <=(currentPrice: Double)(implicit order: Order): Boolean = if (order.action() == Types.Action.BUY) {
      price <= currentPrice
    } else {
      price >= currentPrice
    }

    override def toString: String = price.toString
  }

  private def getBoundPrice(bound: Double)(implicit order: Order): BoundPrice = if (order.action() == Types.Action.BUY) {
    BoundPrice(BigDecimal(order.startingPrice() + (order.startingPrice() * bound)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble)
  } else {
    BoundPrice(BigDecimal(order.startingPrice() - (order.startingPrice() * bound)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble)
  }

  private def onSignal(reqId: Int)(implicit client: TWSClient.Actor, clientAdapter: ActorRef[Event]): PartialFunction[(ActorContext[Command], Signal), Behavior[Command]] = {
    case (_, PostStop) =>
      client.tell(Action.Unsubscribe(clientAdapter, Set(EventType.ContractPrice)))
      client.cancelMktData(reqId)
      Behaviors.same
  }

  private def upperBoundReached(reqId: Int, nextLowerBound: Double, nextUpperBound: Double)(implicit client: TWSClient.Actor, order: Order, replyTo: ActorRef[ReplyCommand], clientAdapter: ActorRef[Event]): Behavior[Command] = {
    val lowerBoundPrice = getBoundPrice(nextLowerBound)
    val upperBoundPrice = getBoundPrice(nextUpperBound)
    Behaviors.receiveMessagePartial[Command] {
      case ContractPrice(price) =>
        logger.info(s"profit: $price (lower bound: $lowerBoundPrice)")
        if (lowerBoundPrice >= price) {
          replyTo ! ReplyCommand(price, lowerBoundPrice.price)
          Behaviors.stopped
        } else if (upperBoundPrice <= price) {
          upperBoundReached(reqId, nextLowerBound + 0.001, nextUpperBound + 0.001)
        } else {
          Behaviors.same
        }
    }.receiveSignal(onSignal(reqId))
  }

  private def noProfit(reqId: Int)(implicit client: TWSClient.Actor, order: Order, replyTo: ActorRef[ReplyCommand], clientAdapter: ActorRef[Event]): Behavior[Command] = {
    val upperBoundPrice = getBoundPrice(upperBound)
    Behaviors.receiveMessagePartial[Command] {
      case ContractPrice(price) =>
        logger.info(s"no profit: $price (upper bound: $upperBoundPrice)")
        if (upperBoundPrice <= price) {
          upperBoundReached(reqId, lowerBound, upperBound + 0.001)
        } else {
          Behaviors.same
        }
    }.receiveSignal(onSignal(reqId))
  }

  def apply(replyTo: ActorRef[ReplyCommand])(implicit client: TWSClient.Actor, order: Order, contract: Contract): Behavior[Command] = Behaviors.setup[Command] { context =>
    val reqId = TWSClient.nextReqId
    implicit val clientAdapter: ActorRef[Event] = context.messageAdapter[Event] {
      case Event.Subscribed(eventTypes) if eventTypes(EventType.ContractPrice) => Subscribed
      case Event.ContractPrice(resId, price) if resId == reqId => ContractPrice(price)
      case _ => UnhandledEvent
    }
    client.tell(Action.Subscribe(clientAdapter, Set(EventType.ContractPrice)))
    Behaviors.receiveMessagePartial[Command] {
      case Subscribed =>
        client.reqMktData(reqId, contract)
        implicit val _replyTo: ActorRef[ReplyCommand] = replyTo
        noProfit(reqId)
    }.receiveSignal(onSignal(reqId))
  }

}