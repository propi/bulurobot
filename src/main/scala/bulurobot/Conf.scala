package bulurobot

import com.typesafe.config.ConfigFactory
import configs.syntax._
import configs.{ConfigReader, Result}

/**
  * Created by Vaclav Zeman on 13. 8. 2017.
  */
object Conf {

  private val conf = ConfigFactory.load()

  def apply[A](path: String)(implicit a: ConfigReader[A]): Result[A] = conf.get(path)

}
