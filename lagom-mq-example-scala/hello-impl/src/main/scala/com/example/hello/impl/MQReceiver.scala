package com.example.hello.impl

import javax.jms.{Message, TextMessage}

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.event.slf4j.Logger
import akka.pattern.pipe
import akka.stream.Materializer
import akka.stream.alpakka.jms.{Credentials, JmsSourceSettings}
import akka.stream.alpakka.jms.scaladsl.JmsSource
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.ibm.mq.jms.MQQueueConnectionFactory
import com.ibm.msg.client.wmq.common.CommonConstants
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsError, JsSuccess, Json}

import scala.concurrent.Future
import scala.util.control.NonFatal

class MQReceiver(
    actorSystem: ActorSystem,
    persistentEntityRegistry: PersistentEntityRegistry,
    materializer: Materializer) {

  private val logger = LoggerFactory.getLogger(getClass)

  def processMessage(message: Message): Future[Done] = {
    logger.info(s"Processing message received over MQ.")
    try {
      val json = Json.parse(message.asInstanceOf[TextMessage].getText)
      Json.fromJson[UseGreetingMessageForId](json) match {
        case JsSuccess(UseGreetingMessageForId(id, greeting), _) =>
          logger.info(s"Message parsed as UseGreetingMessageForId($id, $greeting).")
          val ref = persistentEntityRegistry.refFor[HelloEntity](id) // TODO: Get id from message
          logger.info(s"Sending message to HelloEntity ref.")
          ref.ask(UseGreetingMessage(greeting))
        case error: JsError =>
          Future.failed(new Exception(s"Failed to parse JavaScript: $error"))
      }
    } catch {
      case NonFatal(t) => Future.failed(t)
    }
  }

  val sourceSettings: JmsSourceSettings = {
    val queueConnectionFactory = new MQQueueConnectionFactory()
    queueConnectionFactory.setQueueManager("QM1")
    queueConnectionFactory.setChannel("DEV.APP.SVRCONN")
    queueConnectionFactory.setTransportType(CommonConstants.WMQ_CM_BINDINGS_THEN_CLIENT)
    JmsSourceSettings(queueConnectionFactory).withQueue("DEV.QUEUE.1").withCredential(Credentials("app", ""))
  }

  actorSystem.actorOf(
    ClusterSingletonManager.props(
      singletonProps = Props(classOf[MQReceiver.SinkActor], sourceSettings, processMessage(_: Message), materializer),
      terminationMessage = MQReceiver.SinkActor.ClusterTerminate,
      settings = ClusterSingletonManagerSettings(actorSystem)), // TODO: Use withRole?
    name = "consumer")

}

object MQReceiver {
  object SinkActor {
    case object ClusterTerminate
    case object SinkInit
    case object SinkAck
    case object SinkOnComplete
  }

  class SinkActor(jmsSourceSettings: JmsSourceSettings, processMessage: Message => Future[Done], materializer: Materializer) extends Actor {

    import SinkActor._

    var messageCount = 0

    override def preStart(): Unit = {
      //  private val receiveSource: SourceQueueWithComplete[String] = {
      // TODO: Read values from config file


      val jmsSource: Source[Message, NotUsed] = JmsSource(jmsSourceSettings)
      val actorSink: Sink[Message, NotUsed] = Sink.actorRefWithAck[Message](self,
        onInitMessage = SinkActor.SinkInit,
        ackMessage = SinkActor.SinkAck,
        onCompleteMessage = SinkActor.SinkOnComplete)

      jmsSource.to(actorSink).run()(materializer)
      println(s"Connected Sink.actorRefWithAck to $self")
    }

    override def receive: Receive = {
      case SinkInit =>
        println(s"MQReceiver.receive - SinkInit from $sender")
        context.become(listening, discardOld = true)
        sender ! SinkAck
      case ClusterTerminate =>
        println(s"MQReceiver.receive - ClusterTerminate")
        context.stop(self)
      case Status.Failure(throwable) => // Sent by Sink.actorRefWithAck indicating upstream failure
        println(s"MQReceiver.receive - Status.Failure")
        throwable.printStackTrace()
        context.stop(self) // TODO: Log?
      case any =>
        println(s"MQReceiver.receive - any - $any")
    }

    private def listening: Receive = {
      case message: Message => // Sent by Sink.actorRefWithAck
        messageCount += 1
        println(s"MQReceiver.listening - message $messageCount from ${sender()}")
        context.become(processing(sender()), discardOld = true)
        pipe(processMessage(message))(context.dispatcher).pipeTo(self)
      case SinkActor.SinkOnComplete => // Sent by Sink.actorRefWithAck
        println(s"MQReceiver.listening - SinkOnComplete")
        context.stop(self) // TODO: Log?
      case ClusterTerminate => // Sent by ClusterManager
        println(s"MQReceiver.listening - ClusterTerminate")
        context.stop(self)
      case Status.Failure(throwable) => // Sent by Sink.actorRefWithAck indicating upstream failure
        println(s"MQReceiver.listening - Status.Failure")
        throwable.printStackTrace()
        context.stop(self) // TODO: Log?
      case any =>
        println(s"MQReceiver.listening - any - $any")
    }

    private def processing(sinkSender: ActorRef): Receive = {
      case Done => // Sent by pipe(ref.ask).pipeTo
        println(s"MQReceiver.processing - Done")
        context.become(listening, discardOld = true) // back to listening
        println(s"sending message back to $sinkSender")
        sinkSender ! SinkAck
      case _: Throwable => // Sent by pipe(ref.ask).pipeTo
        println(s"MQReceiver.processing - _: Throwable")
        ??? // TODO: Log error
      case Status.Failure(throwable) => // Sent by Sink.actorRefWithAck indicating upstream failure
        println(s"MQReceiver.processing - Status.Failure")
        throwable.printStackTrace()
        context.stop(self) // TODO: Log?
      case ClusterTerminate => // Sent by ClusterManager
        println(s"MQReceiver.processing - ClusterTerminate")
        context.become(processingThenStopping)
      case any =>
        println(s"MQReceiver.processing - any - $any")
    }

    private def processingThenStopping: Receive = {
      case Done => // Sent by pipe(ref.ask).pipeTo
        println(s"MQReceiver.processingThenStopping - Done")
        context.stop(self)
      case _: Throwable => // Sent by pipe(ref.ask).pipeTo
        println(s"MQReceiver.processingThenStopping - _: Throwable")
        context.stop(self) // TODO: Log error
      // Not expecting any messages from Sink.actorRefWithAck or ClusterManager
      case any =>
        println(s"MQReceiver.processingThenStopping - any - $any")
    }

  }

}