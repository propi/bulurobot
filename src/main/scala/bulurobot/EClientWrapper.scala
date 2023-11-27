package bulurobot

import bulurobot.TWSClient.Id
import com.ib.client.{Contract, EClientSocket, ExecutionFilter, Order}

class EClientWrapper(client: EClientSocket, val id: Id) {

  def cancelMktData(id: Int): Unit = {
    //println(s"cancel mkt data $id")
    client.cancelMktData(id)
  }

  def reqMktData(id: Int, contract: Contract): Unit = {
    //println(s"req mkt data $id")
    client.reqMktData(id, contract, "", false, false, null)
  }

  def placeOrder(contract: Contract, order: Order): Int = id.useNextId { id =>
    //println(s"place order $id")
    client.placeOrder(id, contract, order)
    id
  }

  def reqExecutions(id: Int): Unit = {
    //println(s"req executions $id")
    client.reqExecutions(id, new ExecutionFilter())
  }

  def cancelOrder(id: Int): Unit = {
    //println(s"cancel order $id")
    client.cancelOrder(id)
  }

}