package bulurobot

import bulurobot.Email._
import javax.mail.{Message, Session}
import javax.mail.internet.{InternetAddress, MimeMessage}

import scala.language.implicitConversions

/**
  * Created by Vaclav Zeman on 28. 12. 2019.
  */
class Email private(from: Address, to: Seq[Address], body: Body) {

  def send(): Unit = {
    if (Email.activated) {
      val props = System.getProperties
      props.put("mail.transport.protocol", "smtp")
      props.put("mail.smtp.port", "587")
      props.put("mail.smtp.starttls.enable", "true")
      props.put("mail.smtp.auth", "true")

      // Create a Session object to represent a mail session with the specified properties.
      val session = Session.getDefaultInstance(props)

      // Create a message with the specified information.
      val msg = new MimeMessage(session)
      msg.setFrom(from)
      to.foreach(msg.addRecipient(Message.RecipientType.TO, _))
      msg.setSubject(body.subject, "UTF-8")
      msg.setContent(body.text, body.contentType)

      // Add a configuration set header. Comment or delete the
      // next line if you are not using a configuration set
      //msg.setHeader("X-SES-CONFIGURATION-SET", CONFIGSET)

      // Create a transport.
      val transport = session.getTransport

      // Send the message.
      try {
        // Connect to Amazon SES using the SMTP username and password you specified above.
        transport.connect(host, smtpUser, smtpPassword)
        // Send the email.
        transport.sendMessage(msg, msg.getAllRecipients)
      } finally {
        // Close and terminate the connection.
        transport.close()
      }
    }
  }

}

object Email {

  private val activated = Conf[Boolean]("bulurobot.email.activated").value
  private val host = Conf[String]("bulurobot.email.host").value
  private val mainEmail = Conf[String]("bulurobot.email.main-address").value
  private val mainName = Conf[String]("bulurobot.email.main-name").value
  private val smtpUser = Conf[String]("bulurobot.email.smtp-user").value
  private val smtpPassword = Conf[String]("bulurobot.email.smtp-password").value

  case class Address(name: String, email: String)

  object Address {
    def apply(email: String): Address = new Address("", email)

    def default: Address = new Address(mainName, mainEmail)
  }

  case class Body(subject: String, text: String, contentType: String)

  object Body {
    def apply(subject: String, text: String): Body = new Body(subject, text, "text/html; charset=UTF-8")
  }

  implicit def addressToInternetAddress(address: Address): InternetAddress = if (address.name.isEmpty) {
    new InternetAddress(address.email)
  } else {
    new InternetAddress(address.email, address.name, "UTF-8")
  }

  implicit def stringToAddress(email: String): Address = Address(email)

  def apply(from: Address, body: Body, to: Address, tos: Address*): Email = apply(from, body, to +: tos)

  def apply(from: Address, body: Body, to: Seq[Address]): Email = new Email(from, to, body)

  /*def main(args: Array[String]): Unit = {
    Email(Address.default, Body("test info", "Byla provedena nějaká akce!"), "prozeman@gmail.com", "martin@palmovka.cz").send()
  }*/

}