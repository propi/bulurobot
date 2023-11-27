package bulurobot

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, PostStop}
import bulurobot.EmailLogger.EmailMessage.{AddToTotal, Info, OrderInfo}
import bulurobot.TWSClient.{Action, Event, EventType}
import com.ib.client.{CommissionReport, Execution, OrderStatus}
import com.typesafe.scalalogging.Logger

import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by Vaclav Zeman on 28. 12. 2019.
  */
object EmailLogger {

  private val logger = Logger[EmailLogger.type]

  private val addresses = Conf[List[String]]("bulurobot.email.to").value.map(Email.Address(_))

  sealed trait Message

  case object TrySend extends Message

  sealed trait EmailMessage extends Message

  object EmailMessage {

    case class Info(msg: String) extends EmailMessage {
      override def toString: String = now.toString + ": " + msg
    }

    case class OrderInfo(orderId: Int, filled: Boolean)(val client: TWSClient.Actor, val getMessageFromExecution: (Double, Double) => String) extends EmailMessage

    case class AddToTotal(deltaPrice: Double) extends EmailMessage

    case class Error(th: Throwable) extends EmailMessage {
      override def toString: String = now.toString + ": ERROR - " + th.getMessage + "<br />" + th.getStackTrace.iterator.map(x => "&nbsp;&nbsp;&nbsp;&nbsp;at " + x.toString).mkString("<br />")
    }

  }

  private def now = ZonedDateTime.now(ZoneOffset.UTC)

  private def sendMessages(messages: Vector[EmailMessage], tradingStats: TradingStats): Unit = {
    val messagesIt = Iterator("Zprávy od BuluRobota:") ++ (messages.iterator ++ Iterator(EmailMessage.Info(s"Aktuální bilance: ${tradingStats.totalEarnedMoney}"))).map(_.toString) ++ (if (messages.length >= 100) Iterator("Více zpráv se sem nevešlo, více v logu...") else Iterator())
    Email(Email.Address.default, Email.Body("Zprávy od BuluRobota", messagesIt.mkString("<br /><br />")), addresses).send()
  }

  private def messageListener(lastSent: ZonedDateTime, buffer: Vector[EmailMessage], tradingStats: TradingStats): Behavior[Message] = Behaviors.receive[Message] {
    case (context, TrySend) =>
      context.scheduleOnce(10 minutes, context.self, TrySend)
      if (buffer.nonEmpty && now.isAfter(lastSent.plusMinutes(10))) {
        sendMessages(buffer, tradingStats)
        messageListener(now, Vector.empty, tradingStats)
      } else {
        Behaviors.same
      }
    case (_, AddToTotal(deltaPrice)) =>
      messageListener(lastSent, buffer, tradingStats + deltaPrice)
    case (context, orderInfo: OrderInfo) =>
      context.spawnAnonymous(Behaviors.setup[Event] { contextChild =>
        val reqId = TWSClient.nextReqId
        implicit val ec: ExecutionContext = contextChild.executionContext
        orderInfo.client.tell(Action.Subscribe(contextChild.self, Set(EventType.OpenOrder, EventType.ExecDetails, EventType.CommissionDetails, EventType.ExecDetailsEnd)))
        contextChild.scheduleOnce(30 minutes, contextChild.self, Event.CloseTrading)
        logger.info(s"email logging with id: $reqId, orderId: ${orderInfo.orderId}")

        def waitForStats(executions: Set[Execution], commissionReports: Set[CommissionReport]): Behavior[Event] = Behaviors.receiveMessagePartial[Event] {
          case Event.ExecDetails(resId, _, execution) =>
            if ((reqId == resId || resId == -1) && execution.orderId() == orderInfo.orderId) {
              logger.info(s"obtained execution $resId with orderId ${execution.orderId()} and execId ${execution.execId()}")
              waitForStats(executions + execution, commissionReports)
            } else {
              Behaviors.same
            }
          case Event.CommissionDetails(commissionReport) =>
            logger.info(s"obtained commission report with execId ${commissionReport.execId()}")
            waitForStats(executions, commissionReports + commissionReport)
          case Event.ExecDetailsEnd(resId) if reqId == resId =>
            orderInfo.client.tell(Action.UnsubscribeAll(Some(contextChild.self), contextChild.self))
            val executionIds = executions.iterator.map(_.execId()).toSet
            val avgPrice = if (executions.isEmpty) 0.0 else executions.iterator.map(_.avgPrice()).sum / executions.size
            val commission = commissionReports.iterator.filter(x => executionIds(x.execId())).map(_.commission()).sum
            val message = orderInfo.getMessageFromExecution(avgPrice, commission)
            logger.info(message)
            context.self ! Info(message)
            Behaviors.stopped
          case Event.CloseTrading => Behaviors.stopped
        }

        Behaviors.receiveMessagePartial[Event] {
          case Event.Subscribed(eventTypes) if eventTypes(EventType.OpenOrder) && eventTypes(EventType.ExecDetails) && eventTypes(EventType.CommissionDetails) && eventTypes(EventType.ExecDetailsEnd) =>
            logger.info("email logging subscribed.")
            if (orderInfo.filled) {
              contextChild.system.scheduler.scheduleOnce(5 seconds, () => orderInfo.client.reqExecutions(reqId))
              waitForStats(Set.empty, Set.empty)
            } else {
              Behaviors.same
            }
          case Event.CustomEvent("retry") =>
            Behaviors.same
          case Event.OpenOrder(resOrderId, _, _, orderState) if resOrderId == orderInfo.orderId =>
            if (orderState.status() == OrderStatus.Filled) {
              logger.info(s"email logging with id: $reqId, orderId: ${orderInfo.orderId} FILLED")
              contextChild.system.scheduler.scheduleOnce(5 seconds, () => orderInfo.client.reqExecutions(reqId))
              waitForStats(Set.empty, Set.empty)
            } else {
              logger.info(s"email logging with id: $reqId, orderId: ${orderInfo.orderId} SUBMITTED")
              contextChild.scheduleOnce(5 seconds, contextChild.self, Event.CustomEvent("retry"))
              Behaviors.same
            }
          case Event.CloseTrading => Behaviors.stopped
        }
      })
      Behaviors.same
    case (_, em: EmailMessage) =>
      messageListener(lastSent, if (buffer.length > 100) buffer else buffer :+ em, tradingStats)
  }.receiveSignal {
    case (_, PostStop) if buffer.nonEmpty =>
      sendMessages(buffer, tradingStats)
      Behaviors.same
  }

  def apply(): Behavior[EmailMessage] = Behaviors.setup[Message] { context =>
    val tradingStats = TradingStats()
    context.scheduleOnce(10 minutes, context.self, TrySend)
    messageListener(now, Vector.empty, tradingStats)
  }.narrow[EmailMessage]

}
