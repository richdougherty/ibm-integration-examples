package com.example.hello.impl.mq

import akka.stream.alpakka.jms.scaladsl.{JmsSink, JmsSource}
import akka.stream.alpakka.jms.{Credentials, JmsSinkSettings, JmsSourceSettings}
import com.example.hello.impl.HelloJmsComponents.{RunSink, RunSource}
import com.example.hello.impl.{HelloJmsComponents, HelloJmsSinkFactory, HelloJmsSourceFactory}
import com.ibm.mq.jms.MQQueueConnectionFactory
import com.ibm.msg.client.wmq.common.CommonConstants
import play.api.{BuiltInComponents, Configuration}

trait MQHelloJmsComponents extends HelloJmsComponents {
  self: BuiltInComponents =>

  private lazy val (sourceSettings, sinkSettings) = {

    // Read configuration
    val mqConfig = configuration.get[Configuration]("hello.mq")
    val queueManager = mqConfig.get[String]("queue-manager")
    val channel = mqConfig.get[String]("channel")
    val username = configuration.get[String]("username")
    val password = configuration.get[String]("password")
    val queue = mqConfig.get[String]("queue")

    // Create the connection factory
    val queueConnectionFactory: MQQueueConnectionFactory = new MQQueueConnectionFactory()
    queueConnectionFactory.setQueueManager(queueManager)
    queueConnectionFactory.setChannel(channel)
      // Try to use a native (shared memory) connection if possible,
      // otherwise fall back to using the TCP client connection.
    queueConnectionFactory.setTransportType(CommonConstants.WMQ_CM_BINDINGS_THEN_CLIENT)

    // Create the credentials
    val credentials = Credentials(username, password)

    // Create the source and sink settings
    (
        JmsSourceSettings(queueConnectionFactory)
            .withQueue(queue)
            .withCredential(credentials),
        JmsSinkSettings(queueConnectionFactory)
            .withQueue(queue).withCredential(credentials)
    )
  }

  override def helloJmsSource: HelloJmsSourceFactory = new HelloJmsSourceFactory {
    override def createJmsSource(run: RunSource): Unit = {
      run(JmsSource.textSource(sourceSettings))
    }
  }
  override def helloJmsSink: HelloJmsSinkFactory = new HelloJmsSinkFactory {
    override def createJmsSink(run: RunSink): Unit = {
      run(JmsSink.textSink(sinkSettings))
    }
  }
}
