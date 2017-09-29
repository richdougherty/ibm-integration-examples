package com.example.hello.impl

import com.example.hello.api.HelloService
import com.example.hello.impl.mq.MQHelloJmsComponents
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server._
import com.softwaremill.macwire._
import play.api.libs.ws.ahc.AhcWSComponents

class HelloLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new HelloApplication(context) with MQHelloJmsComponents {
      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new HelloApplication(context) with MQHelloJmsComponents with LagomDevModeComponents

  override def describeService = Some(readDescriptor[HelloService])
}

abstract class HelloApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with LagomKafkaComponents
    with AhcWSComponents
    with HelloJmsComponents {

  // Bind the service that this server provides
  override lazy val lagomServer = serverFor[HelloService](wire[HelloServiceImpl])

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = HelloSerializerRegistry

  // Register the Hello persistent entity
  persistentEntityRegistry.register(wire[HelloEntity])

  lazy val jmsUpdateSender = wire[JmsUpdateSender]

  lazy val jmsUpdateReceiver = wire[JmsUpdateReceiver]

  // Ask MacWire to initialize the JmsListenerActor as a cluster singleton
  wireWith(JmsReceiverActor.startWithClusterSingletonManager _)
}