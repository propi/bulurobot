package bulurobot

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop, Scheduler}
import bulurobot.EmailLogger.EmailMessage
import bulurobot.Main.MainMessage.ClientObtained
import bulurobot.TWSClient.{Event, EventType, Id}
import com.ib.client._
import com.typesafe.scalalogging.Logger

import java.util.concurrent.atomic.AtomicInteger
import java.{lang, util}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Promise}
import scala.language.{implicitConversions, postfixOps}
import scala.util.Random

/**
  * Created by Vaclav Zeman on 13. 9. 2019.
  */
private class TWSClient(implicit emailLogger: ActorRef[EmailMessage], scheduler: Scheduler) extends EWrapper {

  private val logger = Logger[TWSClient]
  private implicit val ec: ExecutionContext = ExecutionContext.global

  private val subscribers: collection.mutable.Map[EventType, collection.mutable.Set[ActorRef[Event]]] = collection.mutable.Map.empty

  private val _id: Promise[Id] = Promise()

  private lazy val _client: EClientSocket = {
    val readerSignal = new EJavaSignal
    val client = new EClientSocket(this, readerSignal)
    client.eConnect(TWSClient.server, TWSClient.port, 1)
    Thread.sleep(2000)
    while (!client.isConnected) {
      logger.info("[INFO] Please make sure you have TWS open and running on port 7496. Retrying connection...")
      client.eConnect(TWSClient.server, TWSClient.port, 1)
      Thread.sleep(2000)
    }
    logger.info("Connected!")
    val reader = new EReader(client, readerSignal)
    reader.start()
    new Thread(() => {
      while (client.isConnected) {
        readerSignal.waitForSignal()
        try
          reader.processMsgs()
        catch {
          case e: Exception =>
            e.printStackTrace()
            logger.info("Error in reader: " + e.getMessage)
            emailLogger ! EmailMessage.Error(e)
        }
      }
    }).start()
    client.reqAccountSummary(1, "All", "AccountType")
    client.reqMarketDataType(if (Main.delayed) 3 else 1)
    client.reqIds(-1)
    scheduler.scheduleWithFixedDelay(2 seconds, 2 seconds)(() => client.reqOpenOrders())
    client
  }

  private def processEvent(eventType: EventType)(f: ActorRef[Event] => Unit): Unit = {
    for (actor <- subscribers.get(eventType).iterator.flatten) {
      f(actor)
    }
  }

  def tickPrice(i: Int, i1: Int, v: Double, tickAttrib: TickAttrib): Unit = {
    logger.debug(s"Tick price: req $i, type $i1, price $v.")
    if ((i1 == 4 && !Main.delayed) || (i1 == 67 && Main.delayed)) {
      processEvent(EventType.ContractPrice) { actor =>
        actor ! Event.ContractPrice(i, v)
      }
    }
  }

  def tickSize(i: Int, i1: Int, i2: Int): Unit = {}

  def tickOptionComputation(i: Int, i1: Int, v: Double, v1: Double, v2: Double, v3: Double, v4: Double, v5: Double, v6: Double, v7: Double): Unit = {}

  def tickGeneric(i: Int, i1: Int, v: Double): Unit = {}

  def tickString(i: Int, i1: Int, s: String): Unit = {}

  def tickEFP(i: Int, i1: Int, v: Double, s: String, v1: Double, i2: Int, s1: String, v2: Double, v3: Double): Unit = {}

  def orderStatus(i: Int, s: String, v: Double, v1: Double, v2: Double, i1: Int, i2: Int, v3: Double, i3: Int, s1: String, v4: Double): Unit = {}

  def openOrder(i: Int, contract: Contract, order: Order, orderState: OrderState): Unit = {
    processEvent(EventType.OpenOrder) { actor =>
      actor ! Event.OpenOrder(i, contract, order, orderState)
    }
  }

  def openOrderEnd(): Unit = {}

  def updateAccountValue(s: String, s1: String, s2: String, s3: String): Unit = {}

  def updatePortfolio(contract: Contract, v: Double, v1: Double, v2: Double, v3: Double, v4: Double, v5: Double, s: String): Unit = {}

  def updateAccountTime(s: String): Unit = {}

  def accountDownloadEnd(s: String): Unit = {}

  def nextValidId(i: Int): Unit = {
    logger.info(s"Next valid id is: $i")
    if (!_id.isCompleted) _id.success(new Id(new AtomicInteger(i)))
  }

  def contractDetails(i: Int, contractDetails: ContractDetails): Unit = {
    processEvent(EventType.ContractDetails) { actor =>
      actor ! Event.ContractDetail(i, contractDetails)
    }
  }

  def bondContractDetails(i: Int, contractDetails: ContractDetails): Unit = {}

  def contractDetailsEnd(i: Int): Unit = {}

  def execDetails(i: Int, contract: Contract, execution: Execution): Unit = {
    processEvent(EventType.ExecDetails) { actor =>
      actor ! Event.ExecDetails(i, contract, execution)
    }
  }

  def execDetailsEnd(i: Int): Unit = {
    processEvent(EventType.ExecDetailsEnd) { actor =>
      actor ! Event.ExecDetailsEnd(i)
    }
  }

  def updateMktDepth(i: Int, i1: Int, i2: Int, i3: Int, v: Double, i4: Int): Unit = {}

  def updateMktDepthL2(i: Int, i1: Int, s: String, i2: Int, i3: Int, v: Double, i4: Int, b: Boolean): Unit = {}

  def updateNewsBulletin(i: Int, i1: Int, s: String, s1: String): Unit = {}

  def managedAccounts(s: String): Unit = {}

  def receiveFA(i: Int, s: String): Unit = {}

  def historicalData(i: Int, bar: Bar): Unit = {}

  def scannerParameters(s: String): Unit = {}

  def scannerData(i: Int, i1: Int, contractDetails: ContractDetails, s: String, s1: String, s2: String, s3: String): Unit = {}

  def scannerDataEnd(i: Int): Unit = {}

  def realtimeBar(i: Int, l: Long, v: Double, v1: Double, v2: Double, v3: Double, l1: Long, v4: Double, i1: Int): Unit = {}

  def currentTime(l: Long): Unit = {}

  def fundamentalData(i: Int, s: String): Unit = {}

  def deltaNeutralValidation(i: Int, deltaNeutralContract: DeltaNeutralContract): Unit = {}

  def tickSnapshotEnd(i: Int): Unit = {}

  def marketDataType(i: Int, i1: Int): Unit = {}

  def commissionReport(commissionReport: CommissionReport): Unit = {
    processEvent(EventType.CommissionDetails) { actor =>
      actor ! Event.CommissionDetails(commissionReport)
    }
  }

  def position(s: String, contract: Contract, v: Double, v1: Double): Unit = {}

  def positionEnd(): Unit = {}

  def accountSummary(i: Int, s: String, s1: String, s2: String, s3: String): Unit = {
    logger.info(s"Account detail: $i, $s, $s1, $s2, $s3")
  }

  def accountSummaryEnd(i: Int): Unit = {}

  def verifyMessageAPI(s: String): Unit = {}

  def verifyCompleted(b: Boolean, s: String): Unit = {}

  def verifyAndAuthMessageAPI(s: String, s1: String): Unit = {}

  def verifyAndAuthCompleted(b: Boolean, s: String): Unit = {}

  def displayGroupList(i: Int, s: String): Unit = {}

  def displayGroupUpdated(i: Int, s: String): Unit = {}

  def error(e: Exception): Unit = {}

  def error(s: String): Unit = {}

  def error(i: Int, i1: Int, s: String): Unit = {}

  def connectionClosed(): Unit = {}

  def connectAck(): Unit = {}

  def positionMulti(i: Int, s: String, s1: String, contract: Contract, v: Double, v1: Double): Unit = {}

  def positionMultiEnd(i: Int): Unit = {}

  def accountUpdateMulti(i: Int, s: String, s1: String, s2: String, s3: String, s4: String): Unit = {}

  def accountUpdateMultiEnd(i: Int): Unit = {}

  def securityDefinitionOptionalParameter(i: Int, s: String, i1: Int, s1: String, s2: String, set: util.Set[String], set1: util.Set[lang.Double]): Unit = {}

  def securityDefinitionOptionalParameterEnd(i: Int): Unit = {}

  def softDollarTiers(i: Int, softDollarTiers: Array[SoftDollarTier]): Unit = {}

  def familyCodes(familyCodes: Array[FamilyCode]): Unit = {}

  def symbolSamples(i: Int, contractDescriptions: Array[ContractDescription]): Unit = {}

  def historicalDataEnd(i: Int, s: String, s1: String): Unit = {}

  def mktDepthExchanges(depthMktDataDescriptions: Array[DepthMktDataDescription]): Unit = {}

  def tickNews(i: Int, l: Long, s: String, s1: String, s2: String, s3: String): Unit = {}

  def smartComponents(i: Int, map: util.Map[Integer, util.Map.Entry[String, Character]]): Unit = {}

  def tickReqParams(i: Int, v: Double, s: String, i1: Int): Unit = {}

  def newsProviders(newsProviders: Array[NewsProvider]): Unit = {}

  def newsArticle(i: Int, i1: Int, s: String): Unit = {}

  def historicalNews(i: Int, s: String, s1: String, s2: String, s3: String): Unit = {}

  def historicalNewsEnd(i: Int, b: Boolean): Unit = {}

  def headTimestamp(i: Int, s: String): Unit = {}

  def histogramData(i: Int, list: util.List[HistogramEntry]): Unit = {}

  def historicalDataUpdate(i: Int, bar: Bar): Unit = {}

  def rerouteMktDataReq(i: Int, i1: Int, s: String): Unit = {}

  def rerouteMktDepthReq(i: Int, i1: Int, s: String): Unit = {}

  def marketRule(i: Int, priceIncrements: Array[PriceIncrement]): Unit = {}

  def pnl(i: Int, v: Double, v1: Double, v2: Double): Unit = {}

  def pnlSingle(i: Int, i1: Int, v: Double, v1: Double, v2: Double, v3: Double): Unit = {}

  def historicalTicks(i: Int, list: util.List[HistoricalTick], b: Boolean): Unit = {}

  def historicalTicksBidAsk(i: Int, list: util.List[HistoricalTickBidAsk], b: Boolean): Unit = {}

  def historicalTicksLast(i: Int, list: util.List[HistoricalTickLast], b: Boolean): Unit = {}

  def tickByTickAllLast(i: Int, i1: Int, l: Long, v: Double, i2: Int, tickAttribLast: TickAttribLast, s: String, s1: String): Unit = {}

  def tickByTickBidAsk(i: Int, l: Long, v: Double, v1: Double, i1: Int, i2: Int, tickAttribBidAsk: TickAttribBidAsk): Unit = {}

  def tickByTickMidPoint(i: Int, l: Long, v: Double): Unit = {}

  def orderBound(l: Long, i: Int, i1: Int): Unit = {}

  def completedOrder(contract: Contract, order: Order, orderState: OrderState): Unit = {}

  def completedOrdersEnd(): Unit = {}
}

object TWSClient {

  val server: String = Conf[String]("tws.server").value
  val port: Int = if (Main.isDemo) 7497 else Conf[Int]("tws.port").value

  def nextReqId: Int = Random.nextInt(1000000) + 1000

  final class Id(value: AtomicInteger) {
    def useNextId[T](f: Int => T): T = value.synchronized {
      val nextId = value.incrementAndGet()
      f(nextId)
    }

    def currentId: Int = value.get()
  }

  private val logger = Logger[TWSClient.type]

  case class Actor(actorRef: ActorRef[Action], client: EClientWrapper)

  implicit def actorToEClientSocket(actor: Actor): EClientWrapper = actor.client

  implicit def actorToActorRef(actor: Actor): ActorRef[Action] = actor.actorRef

  sealed trait Event

  object Event {

    final case class Subscribed(eventTypes: Set[EventType]) extends Event

    final case class Unsubscribed(eventTypes: Set[EventType]) extends Event

    final case class ContractDetail(reqId: Int, contractDetails: ContractDetails) extends Event

    final case class ContractPrice(reqId: Int, price: Double) extends Event

    final case class OpenOrder(orderId: Int, contract: Contract, order: Order, orderState: OrderState) extends Event

    final case class ExecDetails(reqId: Int, contract: Contract, execution: Execution) extends Event

    final case class CommissionDetails(commissionReport: CommissionReport) extends Event

    final case class CustomEvent[T](value: T) extends Event

    final case class ExecDetailsEnd(reqId: Int) extends Event

    final case object CloseTrading extends Event

  }

  sealed trait EventType

  object EventType {

    case object ContractDetails extends EventType

    case object ContractPrice extends EventType

    case object OpenOrder extends EventType

    case object ExecDetails extends EventType

    case object ExecDetailsEnd extends EventType

    case object CommissionDetails extends EventType

  }

  sealed trait Action

  object Action {

    final case class Subscribe(actorRef: ActorRef[Event], eventTypes: Set[EventType]) extends Action

    final case class Unsubscribe(actorRef: ActorRef[Event], eventTypes: Set[EventType]) extends Action

    final case class UnsubscribeAll(actorRef: Option[ActorRef[Event]], replyTo: ActorRef[Event]) extends Action

    final case class GetClient(actorRef: ActorRef[ClientObtained]) extends Action

  }

  def apply()(implicit emailLogger: ActorRef[EmailMessage]): Behavior[Action] = Behaviors.setup[Action] { context =>
    implicit val ec: ExecutionContext = context.executionContext
    implicit val scheduler: Scheduler = context.system.scheduler
    val client = new TWSClient
    Behaviors.receiveMessage[Action] {
      case Action.Subscribe(actorRef, eventTypes) =>
        for (eventType <- eventTypes) {
          client.subscribers.getOrElseUpdate(eventType, collection.mutable.LinkedHashSet.empty) += actorRef
        }
        logger.debug(s"subscribtions: ${client.subscribers}")
        actorRef ! Event.Subscribed(eventTypes)
        Behaviors.same
      case Action.Unsubscribe(actorRef, eventTypes) =>
        for (eventType <- eventTypes) {
          client.subscribers.get(eventType).foreach(_ -= actorRef)
        }
        logger.debug(s"subscribtions: ${client.subscribers}")
        actorRef ! Event.Unsubscribed(eventTypes)
        Behaviors.same
      case Action.UnsubscribeAll(actorRef, replyTo) =>
        val unsubscribedEventTypes = (for ((eventType, actorSet) <- client.subscribers.iterator if actorRef.forall(actorSet(_))) yield {
          actorRef.foreach(actorSet -= _)
          eventType
        }).toSet
        logger.debug(s"subscribtions: ${client.subscribers}")
        replyTo ! Event.Unsubscribed(unsubscribedEventTypes)
        Behaviors.same
      case Action.GetClient(actorRef) =>
        val x = client._client
        client._id.future.foreach(id => actorRef ! ClientObtained(new EClientWrapper(x, id)))
        Behaviors.same
    }.receiveSignal {
      case (_, PostStop) =>
        client._client.eDisconnect()
        Behaviors.same
    }
  }

}