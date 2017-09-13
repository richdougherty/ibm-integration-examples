package com.example.hello.impl

import javax.jms.Message

import akka.pattern.pipe
import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.stream.Materializer
import akka.stream.alpakka.jms.scaladsl.JmsSource
import akka.stream.alpakka.jms.{Credentials, JmsSourceSettings}
import akka.stream.scaladsl.{Sink, Source}
import com.ibm.mq.jms.MQQueueConnectionFactory
import com.ibm.msg.client.wmq.common.CommonConstants
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import play.api.libs.json.{JsError, JsSuccess, Json}

import scala.util.{Failure, Success}

class MQReceiver(actorSystem: ActorSystem, persistentEntityRegistry: PersistentEntityRegistry, materializer: Materializer) {

  actorSystem.actorOf(
    ClusterSingletonManager.props(
      singletonProps = Props(classOf[MQReceiver.SinkActor], persistentEntityRegistry, materializer),
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

  class SinkActor(persistentEntityRegistry: PersistentEntityRegistry, materializer: Materializer) extends Actor {

    import SinkActor._
    override def preStart(): Unit = {
      //  private val receiveSource: SourceQueueWithComplete[String] = {
      // TODO: Read values from config file
      val queueConnectionFactory = new MQQueueConnectionFactory()
      queueConnectionFactory.setQueueManager("QM1")
      queueConnectionFactory.setChannel("DEV.APP.SVRCONN")
      queueConnectionFactory.setTransportType(CommonConstants.WMQ_CM_BINDINGS_THEN_CLIENT)

      val jmsSource: Source[Message, NotUsed] = JmsSource(
        JmsSourceSettings(queueConnectionFactory).withQueue("DEV.QUEUE.1").withCredential(Credentials("app", ""))
      )

      val actorSink: Sink[Any, NotUsed] = Sink.actorRefWithAck(self,
        onInitMessage = SinkActor.SinkInit,
        ackMessage = SinkActor.SinkAck,
        onCompleteMessage = SinkActor.SinkOnComplete)

      jmsSource.to(actorSink).run()(materializer)
      println(s"Connected Sink.actorRefWithAck to $self")
    }

    override def receive: Receive = {
      case SinkInit =>
        println(s"MQReceiver.receive - SinkInit")
        sender ! SinkAck
        context.become(listening)
      case ClusterTerminate =>
        println(s"MQReceiver.receive - ClusterTerminate")
        context.stop(self)
      case Status.Failure(_) => // Sent by Sink.actorRefWithAck indicating upstream failure
        println(s"MQReceiver.receive - Status.Failure")
        context.stop(self) // TODO: Log?
    }

    private def listening: Receive = {
      case message: String => // Sent by Sink.actorRefWithAck
        println(s"MQReceiver.listening - message: String")
        val json = Json.parse(message)
        Json.fromJson[UseGreetingMessageForId](json) match {
          case JsSuccess(UseGreetingMessageForId(id, greeting), _) =>
            println(s"MQReceiver.listening / parse JsSuccess")
            val ref = persistentEntityRegistry.refFor[HelloEntity](id) // TODO: Get id from message
            context.become(processing(sender))
            pipe(ref.ask(UseGreetingMessage(greeting)))(context.dispatcher).pipeTo(self)
          case _: JsError => ??? // TODO: Log error
        }
      case SinkActor.SinkOnComplete => // Sent by Sink.actorRefWithAck
        println(s"MQReceiver.listening - SinkOnComplete")
        context.stop(self) // TODO: Log?
      case ClusterTerminate => // Sent by ClusterManager
        println(s"MQReceiver.listening - ClusterTerminate")
        context.stop(self)
      case Status.Failure(_) => // Sent by Sink.actorRefWithAck indicating upstream failure
        println(s"MQReceiver.listening - Status.Failure")
        context.stop(self) // TODO: Log?
    }

    private def processing(sinkSender: ActorRef): Receive = {
      case Done => // Sent by pipe(ref.ask).pipeTo
        println(s"MQReceiver.processing - Done")
        sinkSender != SinkAck
        context.unbecome() // back to listening
      case _: Throwable => // Sent by pipe(ref.ask).pipeTo
        println(s"MQReceiver.processing - _: Throwable")
        ??? // TODO: Log error
      case Status.Failure(_) => // Sent by Sink.actorRefWithAck indicating upstream failure
        println(s"MQReceiver.processing - Status.Failure")
        context.stop(self) // TODO: Log?
      case ClusterTerminate => // Sent by ClusterManager
        println(s"MQReceiver.processing - ClusterTerminate")
        context.become(processingThenStopping)
    }

    private def processingThenStopping: Receive = {
      case Done => // Sent by pipe(ref.ask).pipeTo
        println(s"MQReceiver.processingThenStopping - Done")
        context.stop(self)
      case _: Throwable => // Sent by pipe(ref.ask).pipeTo
        println(s"MQReceiver.processingThenStopping - _: Throwable")
        context.stop(self) // TODO: Log error
      // Not expecting any messages from Sink.actorRefWithAck or ClusterManager
    }

  }
}