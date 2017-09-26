package com.example.hello.impl

import akka.{Done, NotUsed}
import akka.stream.alpakka.jms.scaladsl.JmsSink
import akka.stream.alpakka.jms.{Credentials, JmsSinkSettings}
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, QueueOfferResult}
import com.ibm.mq.jms.MQQueueConnectionFactory
import com.ibm.msg.client.wmq.common.CommonConstants
import org.slf4j.LoggerFactory
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

trait MQSender {
  def sendGreetingUpdate(id: String, newMessage: String): Future[Done]
}

class MQSenderImpl(
    mqConfiguration: MQConfiguration,
    applicationLifecycle: ApplicationLifecycle,
    materializer: Materializer,
    ec: ExecutionContext) extends MQSender {

  private val logger = LoggerFactory.getLogger(getClass)

  logger.info(s"Starting ${getClass.getName}")

  private val sendSink: SourceQueueWithComplete[String] = {
    // TODO: Read values from config file
    val queueConnectionFactory = new MQQueueConnectionFactory()
    queueConnectionFactory.setQueueManager(mqConfiguration.queueManager)
    queueConnectionFactory.setChannel(mqConfiguration.channel)
    queueConnectionFactory.setTransportType(CommonConstants.WMQ_CM_BINDINGS_THEN_CLIENT)
    val credentials = Credentials(mqConfiguration.username, mqConfiguration.password)
    val jmsSinkSettings = JmsSinkSettings(queueConnectionFactory).withQueue(mqConfiguration.queue).withCredential(credentials)

    val jmsSink: Sink[String, NotUsed] = JmsSink.textSink(jmsSinkSettings)
    val queueSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](0, OverflowStrategy.backpressure)

    queueSource.toMat(jmsSink)(Keep.left).run()(materializer) // TODO: Make implicit
  }

  applicationLifecycle.addStopHook { () =>
    logger.info(s"Stopping ${getClass.getName}")
    sendSink.complete()
    sendSink.watchCompletion()
  }

  override def sendGreetingUpdate(id: String, newMessage: String): Future[Done] = {
    logger.info(s"Sending greeting update to '$id' with message '$newMessage'.")
    val update = UpdateGreetingMessage(id, newMessage)
    val updateJson: JsValue = Json.toJson(update)
    val updateString: String = Json.stringify(updateJson)
    logger.info(s"Encoded JMS message as $updateString")
    sendSink.offer(updateString).map {
      case QueueOfferResult.Enqueued => Done
      case QueueOfferResult.Failure(t) => throw t
      case QueueOfferResult.QueueClosed => throw new IllegalStateException("MQSender was closed")
      case QueueOfferResult.Dropped => throw new Exception("MQSender dropped the message")
    }(ec)
  }

  // TODO: support byte messages
  // TODO: transactional semantics?

}
