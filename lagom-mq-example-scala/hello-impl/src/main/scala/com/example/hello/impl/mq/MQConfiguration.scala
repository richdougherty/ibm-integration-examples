package com.example.hello.impl.mq

import org.slf4j.LoggerFactory
import play.api.Configuration

case class MQConfiguration(
    queueManager: String,
    channel: String,
    queue: String,
    username: String,
    password: String
)

object MQConfiguration {

  private val logger = LoggerFactory.getLogger(getClass)

  def parse(configuration: Configuration): MQConfiguration = {
    val mqConfiguration = MQConfiguration(
      queueManager = configuration.get[String]("queue-manager"),
      channel = configuration.get[String]("channel"),
      queue = configuration.get[String]("queue"),
      username = configuration.get[String]("username"),
      password = configuration.get[String]("password")
    )
    logger.info(s"Parsed MQConfiguration: $mqConfiguration")
    mqConfiguration
  }
}