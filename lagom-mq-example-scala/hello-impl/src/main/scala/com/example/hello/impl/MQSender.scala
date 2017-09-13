package com.example.hello.impl

import akka.NotUsed
import akka.stream.alpakka.jms.scaladsl.JmsSink
import akka.stream.alpakka.jms.{Credentials, JmsSinkSettings}
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, QueueOfferResult}
import com.ibm.mq.jms.MQQueueConnectionFactory
import com.ibm.msg.client.wmq.common.CommonConstants
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json

import scala.concurrent.Future

class MQSender(applicationLifecycle: ApplicationLifecycle, materializer: Materializer) {

  // TODO: Log some INFO messages

  def send(element: UseGreetingMessageForId): Future[QueueOfferResult] = {
    println(s"MQSender.send($element)")
    val json = Json.toJson(element)
    val string = Json.stringify(json)
    sendSink.offer(string)
  }

  private val sendSink: SourceQueueWithComplete[String] = {
    // TODO: Read values from config file
    val queueConnectionFactory = new MQQueueConnectionFactory()
    queueConnectionFactory.setQueueManager("QM1")
    queueConnectionFactory.setChannel("DEV.APP.SVRCONN")
    queueConnectionFactory.setTransportType(CommonConstants.WMQ_CM_BINDINGS_THEN_CLIENT)

    val jmsSink: Sink[String, NotUsed] = JmsSink(
      JmsSinkSettings(queueConnectionFactory).withQueue("DEV.QUEUE.1").withCredential(Credentials("app", ""))
    )
    val queueSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](0, OverflowStrategy.backpressure)

    queueSource.toMat(jmsSink)(Keep.left).run()(materializer) // TODO: Make implicit
  }

  applicationLifecycle.addStopHook { () =>
    sendSink.complete()
    sendSink.watchCompletion()
  }

  // TODO: support byte messages
  // TODO: transactional semantics?

}
