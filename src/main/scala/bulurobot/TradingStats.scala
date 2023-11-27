package bulurobot

import java.io.{File, PrintWriter}

import bulurobot.TradingStats._

import scala.io.Source
import scala.util.Try

/**
  * Created by Vaclav Zeman on 5. 1. 2020.
  */
case class TradingStats private(totalEarnedMoney: Double) {

  def +(earnedMoney: Double): TradingStats = {
    val newValue = totalEarnedMoney + earnedMoney
    val pw = new PrintWriter(statsFile, "UTF-8")
    try {
      pw.println(newValue)
    } finally {
      pw.close()
    }
    TradingStats(newValue)
  }

}

object TradingStats {

  val statsFile: File = Conf[File]("bulurobot.stats-file").value

  def apply(): TradingStats = {
    if (statsFile.exists()) {
      val source = Source.fromFile(statsFile, "UTF-8")
      try {
        TradingStats(source.getLines().find(_ => true).flatMap(x => Try(x.toDouble).toOption).getOrElse(0.0))
      } finally {
        source.close()
      }
    } else {
      TradingStats(0.0)
    }
  }

}