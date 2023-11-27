package bulurobot

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import bulurobot.TWSClient.{Action, Event, EventType}
import bulurobot.Trading.Status
import com.ib.client._
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by Vaclav Zeman on 15. 9. 2019.
  */
object SingleTrade {

  private val currency = Conf[String]("bulurobot.trading.currency").value
  private val exchange = Conf[String]("bulurobot.trading.exchange").value

  private val logger = Logger[SingleTrade.type]

  def cancelOrder(orderId: Int)(implicit client: TWSClient.Actor): Unit = {
    client.cancelOrder(orderId)
    /*Behaviors.setup[Event] { context =>
      client.cancelOrder(orderId)
      client.tell(Action.Subscribe(context.self, Set(EventType.ExecDetails)))
      val reqId = TWSClient.nextReqId
      Behaviors.receiveMessagePartial {
        case Event.Subscribed(eventTypes) if eventTypes(EventType.OpenOrder) && eventTypes(EventType.ExecDetails) =>
          client.reqExecutions(reqId, new ExecutionFilter())
          Behaviors.same
        case Event.ExecDetails(resId, _, execution) if (resId == reqId || resId == -1) && execution.orderId() == orderId =>
          println(execution.cumQty()) // počet nakoupenych akcií
          Behaviors.stopped
      }
    }*/
  }

  def cancelOrder(contract: Contract, order: Order)(implicit client: TWSClient.Actor, emailLogger: ActorRef[EmailLogger.EmailMessage]): Unit = {
    val orderSell = new Order
    orderSell.action(gettingRidOfAction(order))
    orderSell.orderType("MKT")
    orderSell.totalQuantity(order.totalQuantity())
    logger.info(s"Předčasně ukončena objednávka ${contract.symbol()} typu ${order.action()}.")
    val orderId = client.placeOrder(contract, orderSell)
    emailLogger ! EmailLogger.EmailMessage.OrderInfo(orderId, false)(client, (avgPrice, commission) => {
      val total = (if (order.action() == Types.Action.BUY) avgPrice - order.startingPrice() else order.startingPrice() - avgPrice) * order.totalQuantity()
      val totalWithCommissions = total - commission - order.stockRefPrice()
      emailLogger ! EmailLogger.EmailMessage.AddToTotal(totalWithCommissions)
      s"Předčasně ukončena objednávka ${contract.symbol()} typu ${order.action()}. Vykonáno za cenu: $avgPrice. Jak to dopadlo: $total, poplatky START: ${order.stockRefPrice()}, poplatky END: $commission, celkem: $totalWithCommissions"
    })
  }

  /*private def reportOrderEnd(endOrder: Order, startOrder: Order, contract: Contract)(implicit client: TWSClient.Actor, emailLogger: ActorRef[EmailLogger.EmailMessage]) = {
    emailLogger ! EmailLogger.EmailMessage.OrderInfo(endOrder.orderId())(client, (avgPrice, commission) => {
      val total = (if (startOrder.action() == Types.Action.BUY) avgPrice - startOrder.startingPrice() else startOrder.startingPrice() - avgPrice) * startOrder.totalQuantity()
      val totalWithCommissions = total - commission - startOrder.stockRefPrice()
      emailLogger ! EmailLogger.EmailMessage.AddToTotal(totalWithCommissions)
      s"Objednávka ${contract.symbol()} typu ${startOrder.action()} dokončena: $ordStatus. Vykonáno za cenu: $avgPrice. Jak to dopadlo: $total, poplatky START: ${startOrder.stockRefPrice()}, poplatky END: $commission, celkem: $totalWithCommissions"
    })
  }*/

  private def gettingRidOfAction(order: Order): Types.Action = order.action() match {
    case Types.Action.BUY => Types.Action.SELL
    case _ => Types.Action.BUY
  }

  private def cancelOrders(stopOrderId: Int, profitOrderId: Int, actor: ActorRef[Event])(implicit client: TWSClient.Actor, contract: Contract, order: Order, emailLogger: ActorRef[EmailLogger.EmailMessage]): Unit = {
    cancelOrder(profitOrderId)
    cancelOrder(stopOrderId)
    cancelOrder(contract, order)
    client.tell(Action.UnsubscribeAll(Some(actor), actor))
  }

  private def watchOrder(stopOrderId: Int, profitOrderId: Int, allIsActive: Boolean, confirmed: (Boolean, Boolean) = (false, false), lastMessage: Long = System.currentTimeMillis())
                        (implicit client: TWSClient.Actor, contract: Contract, order: Order, trading: ActorRef[Status], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[Event] = {
    Behaviors.receivePartial[Event] {
      case (context, Event.CloseTrading) =>
        logger.info("trade closing...")
        cancelOrders(stopOrderId, profitOrderId, context.self)
        Behaviors.stopped
      case (context, Event.CustomEvent(profitBound: ProfitBoundWatcher.ReplyCommand)) =>
        logger.info(s"profit lower bound (${profitBound.lowerBoundPrice}) reached: ${profitBound.currectPrice}")
        emailLogger ! EmailLogger.EmailMessage.Info(s"Dosažen spodní profitový limit (${profitBound.lowerBoundPrice}).")
        cancelOrders(stopOrderId, profitOrderId, context.self)
        Behaviors.stopped
      case (context, Event.CustomEvent("checkit")) =>
        if (System.currentTimeMillis() > (lastMessage + (10 * 1000))) {
          logger.info("Timeout 10 sec - ruším to")
          cancelOrders(stopOrderId, profitOrderId, context.self)
          Behaviors.stopped
        } else {
          logger.info("Checkuji: vše v pořádku")
          context.scheduleOnce(10 seconds, context.self, Event.CustomEvent("checkit"))
          Behaviors.same
        }
      case (context, Event.OpenOrder(resOrderId, _, endOrder, orderState)) if resOrderId == stopOrderId || resOrderId == profitOrderId =>
        val (stopType, ordStatus) = if (resOrderId == stopOrderId) ("stop loss", "PRODĚLÁNO") else ("stop profit", "VYDĚLÁNO")
        if (orderState.status() == OrderStatus.PreSubmitted || orderState.status() == OrderStatus.Submitted) {
          if (resOrderId == stopOrderId && confirmed._2 || resOrderId == profitOrderId && confirmed._1) {
            if (!allIsActive) {
              trading.tell(Status.AllIsActive)
              if (ProfitBoundWatcher.activated) {
                context.spawn(ProfitBoundWatcher(context.messageAdapter[ProfitBoundWatcher.ReplyCommand](x => Event.CustomEvent(x))), "profit-bound-watcher")
              }
            }
            watchOrder(stopOrderId, profitOrderId, true)
          } else {
            watchOrder(stopOrderId, profitOrderId, allIsActive, (resOrderId == stopOrderId, resOrderId == profitOrderId))
          }
        } else if (orderState.status() == OrderStatus.Filled) {
          emailLogger ! EmailLogger.EmailMessage.OrderInfo(endOrder.orderId(), true)(client, (avgPrice, commission) => {
            val total = (if (order.action() == Types.Action.BUY) avgPrice - order.startingPrice() else order.startingPrice() - avgPrice) * order.totalQuantity()
            val totalWithCommissions = total - commission - order.stockRefPrice()
            emailLogger ! EmailLogger.EmailMessage.AddToTotal(totalWithCommissions)
            s"Objednávka ${contract.symbol()} typu ${order.action()} dokončena: $ordStatus. Vykonáno za cenu: $avgPrice. Jak to dopadlo: $total, poplatky START: ${order.stockRefPrice()}, poplatky END: $commission, celkem: $totalWithCommissions"
          })
          logger.info(s"$stopType: prodano (${gettingRidOfAction(order)})")
          if (resOrderId == stopOrderId) {
            cancelOrder(profitOrderId)
          } else {
            cancelOrder(stopOrderId)
          }
          client.tell(Action.UnsubscribeAll(Some(context.self), context.self))
          Behaviors.stopped
        } else {
          logger.info(s"$stopType: neocekavany stav - " + orderState.status())
          cancelOrders(stopOrderId, profitOrderId, context.self)
          Behaviors.stopped
        }
    }
  }

  private def confirmLimit(stopOrderId: Int, profitOrderId: Int)(implicit client: TWSClient.Actor, contract: Contract, order: Order, trading: ActorRef[Status], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[Event] = {
    Behaviors.receivePartial[Event] {
      case (context, Event.OpenOrder(resOrderId, _, _, orderState)) if resOrderId == profitOrderId =>
        logger.info(s"orders: $resOrderId, ${orderState.status()}")
        if (orderState.status() == OrderStatus.PreSubmitted || orderState.status() == OrderStatus.Filled || orderState.status() == OrderStatus.Submitted) {
          trading.tell(Status.LimitOrderCreated(profitOrderId))
          context.scheduleOnce(10 seconds, context.self, Event.CustomEvent("checkit"))
          watchOrder(stopOrderId, profitOrderId, false)
        } else {
          Behaviors.same
        }
    }
  }

  private def confirmStop(stopOrderId: Int, profitOrderId: Int)(implicit client: TWSClient.Actor, contract: Contract, order: Order, trading: ActorRef[Status], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[Event] = {
    Behaviors.receivePartial[Event] {
      case (context, Event.OpenOrder(resOrderId, _, _, orderState)) if resOrderId == stopOrderId =>
        logger.info(s"orders: $resOrderId, ${orderState.status()}")
        if (orderState.status() == OrderStatus.PreSubmitted || orderState.status() == OrderStatus.Filled || orderState.status() == OrderStatus.Submitted) {
          trading.tell(Status.StopOrderCreated(stopOrderId))
          confirmLimit(stopOrderId, profitOrderId)
        } else {
          Behaviors.same
        }
    }
  }

  private def setStop(orderId: Int, profitThreshold: Double, stopThreshold: Double)(implicit client: TWSClient.Actor, trading: ActorRef[Status], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[Event] = {
    Behaviors.receivePartial[Event] {
      case (context, Event.OpenOrder(resOrderId, contract, order, orderState)) if resOrderId == orderId =>
        if (orderState.status() != OrderStatus.Filled) {
          logger.info(s"Order $orderId není stále vykonán; aktuální stav: ${orderState.status()}")
          Behaviors.same
        } else {
          val reqId = TWSClient.nextReqId
          implicit val ec: ExecutionContext = context.executionContext
          logger.info(s"Order $orderId vykonána; zjišťuji cenu...")
          context.system.scheduler.scheduleOnce(5 seconds, () => client.reqExecutions(reqId))

          def waitForStats(executions: Set[Execution], commissionReports: Set[CommissionReport]): Behavior[Event] = Behaviors.receiveMessagePartial[Event] {
            case Event.ExecDetails(resId, _, execution) if reqId == resId && execution.orderId() == orderId =>
              logger.info(s"obtain execution: $resId, ${execution.avgPrice()}, ${execution.execId()}")
              waitForStats(executions + execution, commissionReports)
            case Event.CommissionDetails(commissionReport) =>
              //logger.info(s"obrain report: ${commissionReport.commission()}, ${commissionReport.hashCode()}, ${commissionReport.execId()}")
              waitForStats(executions, commissionReports + commissionReport)
            case Event.ExecDetailsEnd(resId) if reqId == resId =>
              val executionIds = executions.iterator.map(_.execId()).toSet
              val avgPrice = if (executions.isEmpty) 0.0 else executions.iterator.map(_.avgPrice()).sum / executions.size
              val commission = commissionReports.iterator.filter(x => executionIds(x.execId())).map(_.commission()).sum
              logger.info(s"objednavka $orderId vykonana za cenu $avgPrice s poplatkami $commission")
              order.startingPrice(avgPrice)
              order.stockRefPrice(commission)
              trading.tell(Status.OrderCreated(order, contract))
              val (stopPrice, profitPrice) = if (order.action() == Types.Action.BUY) {
                val stopPrice = BigDecimal(avgPrice - (avgPrice * stopThreshold)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
                val profitPrice = BigDecimal(avgPrice + (avgPrice * profitThreshold)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
                stopPrice -> profitPrice
              } else {
                val stopPrice = BigDecimal(avgPrice + (avgPrice * stopThreshold)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
                val profitPrice = BigDecimal(avgPrice - (avgPrice * profitThreshold)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
                stopPrice -> profitPrice
              }
              val orderStopLoss = new Order
              orderStopLoss.action(gettingRidOfAction(order))
              orderStopLoss.orderType("STP")
              orderStopLoss.auxPrice(stopPrice)
              orderStopLoss.totalQuantity(order.totalQuantity())
              val orderStopProfit = new Order
              orderStopProfit.action(gettingRidOfAction(order))
              orderStopProfit.orderType("LMT")
              orderStopProfit.lmtPrice(profitPrice)
              orderStopProfit.totalQuantity(order.totalQuantity())
              val stopOrderId = client.placeOrder(contract, orderStopLoss)
              val profitOrderId = client.placeOrder(contract, orderStopProfit)
              logger.info(s"odeslan stop pro $stopOrderId s cenou $stopPrice a mnozstvim ${order.totalQuantity()}")
              logger.info(s"odeslan profit pro $profitOrderId s cenou $profitPrice a mnozstvim ${order.totalQuantity()}")
              emailLogger ! EmailLogger.EmailMessage.Info(s"Provedena objednávka ${contract.symbol()} typu ${order.action()} v množství ${order.totalQuantity()} za cenu $avgPrice s poplatkami $commission. STOP THRESHOLD = $stopThreshold, STOP = $stopPrice, PROFIT THRESHOLD = $profitThreshold, PROFIT = $profitPrice")
              confirmStop(stopOrderId, profitOrderId)(client, contract, order, trading, emailLogger)
          }

          waitForStats(Set.empty, Set.empty)
        }
    }
  }

  private def buy(contract: Contract, order: Order, profitThreshold: Double, stopThreshold: Double)(implicit client: TWSClient.Actor, trading: ActorRef[Status], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[Event] = {
    Behaviors.setup[Event] { context =>
      val subscribedEventTypes: Set[EventType] = Set(EventType.OpenOrder, EventType.ExecDetails, EventType.ExecDetailsEnd, EventType.CommissionDetails)
      client.tell(Action.Subscribe(context.self, subscribedEventTypes))
      Behaviors.receiveMessagePartial[Event] {
        case Event.Subscribed(eventTypes) if subscribedEventTypes.forall(eventTypes) =>
          val orderId = client.placeOrder(contract, order)
          logger.info(s"objednáno, id: $orderId")
          trading ! Trading.Status.OrderSubmitted(orderId)
          setStop(orderId, profitThreshold, stopThreshold)
      }
    }
  }

  def apply(symbol: String, maxPurchasePrice: Double, profitThreshold: Double, stopThreshold: Double, tradingType: Trading.TradingType)(implicit client: TWSClient.Actor, trading: ActorRef[Status], emailLogger: ActorRef[EmailLogger.EmailMessage]): Behavior[Event] = {
    Behaviors.setup[Event] { context =>
      val reqId: Int = TWSClient.nextReqId
      val contract = new Contract()
      contract.symbol(symbol)
      contract.secType("STK")
      contract.currency(currency)
      contract.exchange(exchange)
      client.tell(Action.Subscribe(context.self, Set(EventType.ContractPrice)))
      logger.info(s"single trading for $symbol")
      Behaviors.receiveMessagePartial[Event] {
        case Event.Subscribed(eventTypes) if eventTypes(EventType.ContractPrice) =>
          logger.info(s"Request contract data for $symbol, request ID: $reqId")
          client.reqMktData(reqId, contract)
          Behaviors.same
        case Event.ContractPrice(resId, price) =>
          logger.info(s"single trading data response: obtained $resId")
          if (resId == reqId) {
            client.tell(Action.Unsubscribe(context.self, Set(EventType.ContractPrice)))
            client.cancelMktData(resId)
            val quantity = math.floor(maxPurchasePrice / price).toInt
            logger.info(s"Chci ${if (tradingType == Trading.TradingType.Buy) "koupit" else "prodat"} ${contract.symbol()} v množství $quantity za cenu $price")
            val orderBuy = new Order
            orderBuy.action(tradingType match {
              case Trading.TradingType.Buy => Types.Action.BUY
              case Trading.TradingType.Sell => Types.Action.SELL
            })
            orderBuy.orderType("MKT")
            orderBuy.totalQuantity(quantity)
            buy(contract, orderBuy, profitThreshold, stopThreshold)
          } else {
            Behaviors.same
          }
      }
    }
  }

}