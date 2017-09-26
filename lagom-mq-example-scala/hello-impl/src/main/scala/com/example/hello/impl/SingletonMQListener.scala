package com.example.hello.impl

import javax.jms.{Message, TextMessage}

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.pattern.pipe
import akka.stream.Materializer
import akka.stream.alpakka.jms.scaladsl.JmsSource
import akka.stream.alpakka.jms.{Credentials, JmsSourceSettings}
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import com.ibm.mq.jms.MQQueueConnectionFactory
import com.ibm.msg.client.wmq.common.CommonConstants
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}

import scala.concurrent.Future
import scala.util.control.NonFatal

class MQListenerActor(jmsSourceSettings: JmsSourceSettings, mqHandler: MQHandler, materializer: Materializer) extends Actor {

  import MQListenerActor._

  private val logger = LoggerFactory.getLogger(getClass)

  // Implicitly use the dispatcher to execute concurrent operations (e.g. futures)
  import context.dispatcher

  override def preStart(): Unit = {
    //  private val receiveSource: SourceQueueWithComplete[String] = {
    // TODO: Read values from config file

    logger.info("preStart called: creating MQ JmsSource")

    val jmsSource: Source[Message, NotUsed] = JmsSource(jmsSourceSettings)
    val actorSink: Sink[Message, NotUsed] = Sink.actorRefWithAck[Message](self,
      onInitMessage = StreamInit,
      ackMessage = StreamAck,
      onCompleteMessage = StreamComplete,
      onFailureMessage = StreamFailure
    )

    jmsSource.to(actorSink).run()(materializer)
  }

  override def postStop(): Unit = {
    logger.info("postStop called: MQ JmsSource will be shut down by Sink.actorRefWithAck")
  }

  override def receive: Receive = {
    // Messages sent by Sink.actorRefWithAck
    case StreamInit =>
      logger.info("Upstream is ready")
      sender ! StreamAck
      context.become(listening, discardOld = true)
    case StreamComplete =>
      logger.error(s"Terminating because upstream was closed")
      context.stop(self)
    case StreamFailure(throwable) =>
      logger.error(s"Terminating due to upstream failure", throwable)
      context.stop(self)

    // Messages sent by ClusterSingletonManager
    case ClusterSingletonTerminate =>
      logger.info("Terminating at request of ClusterSingletonManager")
      context.stop(self)
  }

  private def listening: Receive = {
    // Messages sent by Sink.actorRefWithAck
    case message: Message =>
      logger.info("Received message: processing")
      context.become(processing(sender()), discardOld = true)
      pipe(handleMessage(message)).pipeTo(self)
    case StreamComplete =>
      logger.error(s"Terminating because upstream was closed")
      context.stop(self)
    case StreamFailure(throwable) =>
      logger.error(s"Terminating due to upstream failure", throwable)
      context.stop(self)

    // Messages sent by ClusterSingletonManager
    case ClusterSingletonTerminate => // Sent by ClusterManager
      logger.info("Terminating at request of ClusterSingletonManager")
      context.stop(self)
  }

  private def processing(sinkSender: ActorRef): Receive = {
    // Messages sent by pipeTo
    case Done =>
      logger.info("Message processing finished: waiting for next message")
      sinkSender ! StreamAck
      context.become(listening, discardOld = true)
    case t: Throwable => // Sent by pipe(ref.ask).pipeTo
      logger.error("Message processing failed: terminating", t)
      context.stop(self)

    // Messages sent by Sink.actorRefWithAck
    case StreamComplete =>
      logger.error(s"Upstream was closed: will terminate once the current message is processed")
      context.become(processingThenStopping(reason = "upstream was closed"), discardOld = true)
    case StreamFailure(throwable) =>
      logger.error(s"Upstream failed: will terminate once the current message is processed", throwable)
      context.become(processingThenStopping(reason = "upstream failed"), discardOld = true)

    // Messages sent by ClusterSingletonManager
    case ClusterSingletonTerminate =>
      logger.info("ClusterSingletonManager asked us to terminate: will terminate once the current message is processed")
      context.become(processingThenStopping(reason = "ClusterSingletonManager asked us to terminate"))
  }

  private def processingThenStopping(reason: String): Receive = {
    // Messages sent by pipeTo
    case Done =>
      logger.info(s"Message processing finished: terminating because $reason")
      context.stop(self)
    case t: Throwable =>
      logger.error(s"Message processing failed: terminating because $reason", t)
      context.stop(self)

    // Messages sent by Sink.actorRefWithAck
    case StreamComplete =>
      logger.error(s"Upstream was closed: already terminating after the current message is processed: $reason")
    case StreamFailure(throwable) =>
      logger.error(s"Upstream failed: already terminating after the current message is processed: $reason", throwable)

    // Messages sent by ClusterSingletonManager
    case ClusterSingletonTerminate =>
      logger.info(s"ClusterSingletonManager asked us to terminate: already terminating after the current message is processed: $reason")
  }

  /** Handle a message that is received. */
  def handleMessage(message: Message): Future[Done] = {
    try {
      logger.info(s"Processing message received over MQ.")
      val updateString: String = message.asInstanceOf[TextMessage].getText
      logger.info(s"Received JMS message: $updateString")
      val updateJson: JsValue = Json.parse(updateString)
      Json.fromJson[UpdateGreetingMessage](updateJson) match {
        case JsSuccess(UpdateGreetingMessage(id, newMessage), _) =>
          logger.info(s"Message parsed as UseGreetingMessageForId($id, $newMessage).")
          mqHandler.handleGreetingUpdate(id, newMessage)
        case error: JsError =>
          throw new Exception(s"Failed to parse JavaScript: $error")
      }
    } catch {
      case NonFatal(t) =>
        logger.error("Error handling message", t)
        Future.failed(t)
    }
  }
}

object MQListenerActor {

  case object ClusterSingletonTerminate
  case object StreamInit
  case object StreamAck
  case object StreamComplete
  case class StreamFailure(t: Throwable)

  class SingletonInitializer(
      mqConfiguration: MQConfiguration,
      mqHandler: MQHandler,
      actorSystem: ActorSystem,
      materializer: Materializer) {

    private val logger = LoggerFactory.getLogger(getClass)

    logger.info(s"Starting SingletonMQListenerActor as a cluster singleton")

    private def sourceSettings: JmsSourceSettings = {
      val queueConnectionFactory = new MQQueueConnectionFactory()
      queueConnectionFactory.setQueueManager(mqConfiguration.queueManager)
      queueConnectionFactory.setChannel(mqConfiguration.channel)
      queueConnectionFactory.setTransportType(CommonConstants.WMQ_CM_BINDINGS_THEN_CLIENT)
      val credentials = Credentials(mqConfiguration.username, mqConfiguration.password)
      JmsSourceSettings(queueConnectionFactory).withQueue(mqConfiguration.queue).withCredential(credentials)
    }

    actorSystem.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props(classOf[MQListenerActor], sourceSettings, mqHandler, materializer),
        terminationMessage = ClusterSingletonTerminate,
        settings = ClusterSingletonManagerSettings(actorSystem)),
      name = "mq-listener")
  }
}