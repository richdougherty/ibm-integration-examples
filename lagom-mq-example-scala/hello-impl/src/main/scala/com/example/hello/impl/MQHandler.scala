package com.example.hello.impl

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

trait MQHandler {
  def handleGreetingUpdate(id: String, newMessage: String): Future[Done]
}

class MQHandlerImpl(
    persistentEntityRegistry: PersistentEntityRegistry) extends MQHandler {
  private val logger = LoggerFactory.getLogger(getClass)
  override def handleGreetingUpdate(id: String, newMessage: String): Future[Done] = {
    try {
      logger.info(s"Updating entity '$id' with message '$newMessage'.")
      val ref = persistentEntityRegistry.refFor[HelloEntity](id) // TODO: Get id from message
      ref.ask(UseGreetingMessage(newMessage))
    } catch {
      case NonFatal(t) =>
        logger.error("Caught exception processing message", t)
        Future.failed(t)
    }
  }
}