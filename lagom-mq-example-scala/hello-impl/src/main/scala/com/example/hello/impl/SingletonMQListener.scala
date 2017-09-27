package com.example.hello.impl

import javax.jms.{Message, TextMessage}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
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

/**
 * An MQListenerActor is responsible for listening to an MQ message queue
 * and invoking an [[MQReceiveHandler]].
 *
 * @param mqConfiguration The configuration used to create the MQ connection.
 * @param receiveHandler This will handle each message received.
 * @param materializer This is used to materialize the stream.
 */
class MQListenerActor(mqConfiguration: MQConfiguration, receiveHandler: MQReceiveHandler, materializer: Materializer) extends Actor {

  import MQListenerActor._

  private val logger = LoggerFactory.getLogger(getClass)

  // Implicitly use the dispatcher to execute concurrent operations (e.g. futures)
  import context.dispatcher

  /**
   * This method is called by the [[ActorSystem]] when this actor
   * is first started or whenever it is restarted.
   */
  override def preStart(): Unit = {
    logger.info("preStart called: creating MQ JmsSource")

    val jmsSource: Source[Message, NotUsed] = {
      val queueConnectionFactory = new MQQueueConnectionFactory()
      queueConnectionFactory.setQueueManager(mqConfiguration.queueManager)
      queueConnectionFactory.setChannel(mqConfiguration.channel)
      // Try to use a native (shared memory) connection if possible,
      // otherwise fall back to using the TCP client connection.
      queueConnectionFactory.setTransportType(CommonConstants.WMQ_CM_BINDINGS_THEN_CLIENT)
      val credentials = Credentials(mqConfiguration.username, mqConfiguration.password)
      val sourceSettings = JmsSourceSettings(queueConnectionFactory).withQueue(mqConfiguration.queue).withCredential(credentials)
      JmsSource(sourceSettings)
    }

    logger.info("preStart called: creating Sink.actorRefWithAck")

    val actorSink: Sink[Message, NotUsed] = Sink.actorRefWithAck[Message](self,
      onInitMessage = StreamInit,
      ackMessage = StreamAck,
      onCompleteMessage = StreamComplete,
      onFailureMessage = StreamFailure
    )

    logger.info("preStart called: creating stream from JmsSource to Sink.actorRefWithAck")
    jmsSource.to(actorSink).run()(materializer)
  }

  /**
   * This method is called by the [[ActorSystem]] when this actor
   * stops or whenever it is stopped just before being restarted.
   */
  override def postStop(): Unit = {
    logger.info("postStop called: stream with JmsSource will be shut down by Sink.actorRefWithAck")
  }

  /**
   * The starting receive handler for this actor.
   *
   * This receive handler is used until the actor receives [[StreamInit]]
   * message at which point the actor changes to use the [[waitingForMessage]]
   * receive handler.
   */
  override def receive: Receive = {
    // Messages sent by Sink.actorRefWithAck
    case StreamInit =>
      logger.info("Upstream is ready: changing actor to listen for messages")
      sender ! StreamAck // Tell upstream that we've finished initializing
      context.become(waitingForMessage, discardOld = true) // Change to listen for messages

    // Messages sent by ClusterSingletonManager
    case ClusterSingletonTerminate =>
      logger.info("Terminating at request of ClusterSingletonManager")
      context.stop(self)
  }

  /**
   * The receive handler used when waiting for MQ [[Message]]s from upstream.
   */
  private def waitingForMessage: Receive = {
    // Messages sent by Sink.actorRefWithAck
    case message: Message =>
      logger.info("Received message: processing")
      context.become(processingMessage(sender()), discardOld = true)
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

  /**
   * The receive handler used when processing an MQ [[Message]] received
   * from upstream.
   *
   * @param streamSender The upstream sender that originally sent the
   *                   MQ [[Message]]. We pass this parameter because
   *                   the sender that sends the next (non-MQ) message,
   *                   e.g. [[Done]], might not be the original sender.
   */
  private def processingMessage(streamSender: ActorRef): Receive = {
    // Messages sent by pipeTo
    case Done =>
      logger.info("Message processing finished: waiting for next message")
      streamSender ! StreamAck // Acknowledge that we've finished processing
      context.become(waitingForMessage, discardOld = true)
    case t: Throwable => // Sent by pipe(ref.ask).pipeTo
      logger.error("Message processing failed: terminating", t)
      context.stop(self)

    // Messages sent by Sink.actorRefWithAck
    case StreamComplete =>
      logger.error(s"Upstream was closed: will terminate once the current message is processed")
      context.become(processingMessageThenStopping(stopReason = "upstream was closed"), discardOld = true)
    case StreamFailure(throwable) =>
      logger.error(s"Upstream failed: will terminate once the current message is processed", throwable)
      context.become(processingMessageThenStopping(stopReason = "upstream failed"), discardOld = true)

    // Messages sent by ClusterSingletonManager
    case ClusterSingletonTerminate =>
      logger.info("ClusterSingletonManager asked us to terminate: will terminate once the current message is processed")
      context.become(processingMessageThenStopping(stopReason = "ClusterSingletonManager asked us to terminate"))
  }

  /**
   * The receive handler used when processing an MQ [[Message]] and then
   * stopping. This might happen if we receive an MQ `Message` but then
   * we're given a reason to stop, e.g. if the upstream stream is closed
   * or if we're asked to stop by the [[ClusterSingletonManager]].
   *
   * @param stopReason A message explaining why we're stopping. Used for log messages.
   */
  private def processingMessageThenStopping(stopReason: String): Receive = {
    // Messages sent by pipeTo
    case Done =>
      logger.info(s"Message processing finished: terminating because $stopReason")
      context.stop(self)
    case t: Throwable =>
      logger.error(s"Message processing failed: terminating because $stopReason", t)
      context.stop(self)

    // Messages sent by Sink.actorRefWithAck
    case StreamComplete =>
      logger.error(s"Upstream was closed: already terminating after the current message is processed: $stopReason")
    case StreamFailure(throwable) =>
      logger.error(s"Upstream failed: already terminating after the current message is processed: $stopReason", throwable)

    // Messages sent by ClusterSingletonManager
    case ClusterSingletonTerminate =>
      logger.info(s"ClusterSingletonManager asked us to terminate: already terminating after the current message is processed: $stopReason")
  }

  /**
   * Handle a message that is received. This decodes the raw [[Message]],
   * getting JSON [[JsValue]], decoding it to an [[UpdateGreetingMessage]]
   * then calling the [[MQReceiveHandler]].
   */
  private def handleMessage(message: Message): Future[Done] = {
    try {
      logger.info(s"Processing message received over MQ.")
      val updateString: String = message.asInstanceOf[TextMessage].getText
      logger.info(s"Received JMS message: $updateString")
      val updateJson: JsValue = Json.parse(updateString)
      Json.fromJson[UpdateGreetingMessage](updateJson) match {
        case JsSuccess(UpdateGreetingMessage(id, newMessage), _) =>
          logger.info(s"Message parsed as UseGreetingMessageForId($id, $newMessage).")
          receiveHandler.handleGreetingUpdate(id, newMessage)
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

  /**
   * This message is sent by the [[ClusterSingletonManager]] to the [[MQListenerActor]]
   * when it is time to terminate.
   */
  case object ClusterSingletonTerminate

  /**
   * This message is sent by [[Sink.actorRefWithAck()]] to the [[MQListenerActor]]
   * when the stream is being initialized.
   */
  case object StreamInit

  /**
   * This message is a reply from the [[MQListenerActor]] to acknowledge a message
   * sent by [[Sink.actorRefWithAck()]]. This message is used to control backpressure;
   * the [[Sink]] will wait until it gets acknowledgement before proceeding.
   */
  case object StreamAck

  /**
   * This message is sent by [[Sink.actorRefWithAck()]] to the [[MQListenerActor]]
   * when the stream is completed successfully.
   */
  case object StreamComplete

  /**
   * This message is sent by [[Sink.actorRefWithAck()]] to the [[MQListenerActor]]
   * when the stream is completed with failure.
   */
  case class StreamFailure(t: Throwable)

  /**
   * A class which initializes the [[MQListenerActor]] singleton. We use a
   * class for initialization instead of a simple function because we want
   * to call this from the [[HelloLoader]] using dependency injection.
   */
  class SingletonInitializer(
      mqConfiguration: MQConfiguration,
      mqHandler: MQReceiveHandler,
      actorSystem: ActorSystem,
      materializer: Materializer) {

    private val logger = LoggerFactory.getLogger(getClass)

    logger.info(s"Starting ClusterSingletonManager to run ${classOf[MQListenerActor].getSimpleName} as a cluster singleton")

    // Start a ClusterSingletonManager actor. This actor will communicate with
    // its peers on the cluster and decide on one cluster member to run the
    // MQListenerActor. If cluster membership changes (e.g. a node dies) then
    // the node running the MQListenerActor may change.
    actorSystem.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props(classOf[MQListenerActor], mqConfiguration, mqHandler, materializer),
        terminationMessage = ClusterSingletonTerminate,
        settings = ClusterSingletonManagerSettings(actorSystem)),
      name = "mq-listener-cluster-singleton-manager")
  }
}