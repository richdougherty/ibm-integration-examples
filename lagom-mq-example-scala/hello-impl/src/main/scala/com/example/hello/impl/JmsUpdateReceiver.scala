package com.example.hello.impl

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * Handles received MQ messages by updating a persistent entity.
 *
 * @param persistentEntityRegistry
 */
class JmsUpdateReceiver(
    persistentEntityRegistry: PersistentEntityRegistry) {
  private val logger = LoggerFactory.getLogger(getClass)
  def handleGreetingUpdate(id: String, newMessage: String): Future[Done] = {
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