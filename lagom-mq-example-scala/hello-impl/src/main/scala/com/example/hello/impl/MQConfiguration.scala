package com.example.hello.impl

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
    val base = configuration.get[Configuration]("hello.mq")
    val mqConfiguration = MQConfiguration(
      queueManager = base.get[String]("queue-manager"),
      channel = base.get[String]("channel"),
      queue = base.get[String]("queue"),
      username = base.get[String]("username"),
      password = base.get[String]("password")
    )
    logger.info(s"Parsed MQConfiguration: $mqConfiguration")
    mqConfiguration
  }
}