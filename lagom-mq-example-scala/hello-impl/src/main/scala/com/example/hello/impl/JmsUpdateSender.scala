package com.example.hello.impl

import akka.Done
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, QueueOfferResult}
import com.example.hello.impl.HelloJmsSinkFactory.RunSink
import org.slf4j.LoggerFactory
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Sends a greeting update over JMS.
 *
 * @param helloJmsSinkFactory Used to create the JMS connection to send to.
 * @param applicationLifecycle Used to ensure MQ shuts down when the application stops.
 * @param materializer Used to create streams.
 * @param ec Used to run futures.
 */
class JmsUpdateSender(
    helloJmsSinkFactory: HelloJmsSinkFactory,
    applicationLifecycle: ApplicationLifecycle,
    materializer: Materializer)(
    implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(getClass)

  logger.info(s"Starting ${getClass.getName}")

  private val sendQueue: SourceQueueWithComplete[String] = {
    @volatile var tmp: SourceQueueWithComplete[String] = null
    logger.info("Starting JmsSink")
    helloJmsSinkFactory.createJmsSink(new RunSink {
      override def apply[T](sink: Sink[String, T]): T = {
        val queueSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](0, OverflowStrategy.backpressure)
        val (sourceQueue, result): (SourceQueueWithComplete[String], T) =
          queueSource.toMat(sink)(Keep.both).run()(materializer)
        tmp = sourceQueue
        result
      }
    })
    tmp
  }

  applicationLifecycle.addStopHook { () =>
    logger.info(s"Stopping ${getClass.getName}")
    sendQueue.complete()
    sendQueue.watchCompletion()
  }

  def sendGreetingUpdate(id: String, newMessage: String): Future[Done] = {
    logger.info(s"Sending greeting update to '$id' with message '$newMessage'.")
    val update = UpdateGreetingMessage(id, newMessage)
    val updateJson: JsValue = Json.toJson(update)
    val updateString: String = Json.stringify(updateJson)
    logger.info(s"Encoded JMS message as $updateString")
    sendQueue.offer(updateString).map {
      case QueueOfferResult.Enqueued => Done
      case QueueOfferResult.Failure(t) => throw t
      case QueueOfferResult.QueueClosed => throw new IllegalStateException("MQSender was closed")
      case QueueOfferResult.Dropped => throw new Exception("MQSender dropped the message")
    }(ec)
  }

}
