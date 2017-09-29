package com.example.hello.impl

import akka.stream.scaladsl.{Sink, Source}
import com.example.hello.impl.HelloJmsSinkFactory.RunSink
import com.example.hello.impl.HelloJmsSourceFactory.RunSource

/**
 * These components are implemented by [[com.example.hello.impl.mq.MQHelloJmsComponents]]
 * in production, but it's helpful to abstract them out so we can mock
 * the for testing.
 */
trait HelloJmsComponents {
  /**
   * Lets us create a [[Source]] from which we can receive Hello service
   * JMS messages.
   */
  def helloJmsSource: HelloJmsSourceFactory

  /**
   * Lets us create a [[Sink]] to which we can send Hello service JMS messages.
   */
  def helloJmsSink: HelloJmsSinkFactory
}

/**
 * Lets us create a [[Source]] which we can use to receive Hello service
 * JMS messages.
 */
trait HelloJmsSourceFactory {
  /**
   * Create a new JMS source. When called, this method should call [[RunSource.apply()]]
   * to actually run the [[Source]].
   */
  def createJmsSource(run: RunSource): Unit
}

object HelloJmsSourceFactory {

  /**
   * This is a function to run a [[Source]]. It will connect the source to a
   * stream, materialize the stream and return the source's materialized value.
   *
   * We use this trait instead of a plain function because we want to have
   * the type parameter on the apply method instead of on the trait.
   */
  trait RunSource {
    def apply[T](source: Source[String, T]): T
  }


}

/**
 * Lets us create a [[Sink]] to which we can send Hello service
 * JMS messages.
 */
trait HelloJmsSinkFactory {
  /**
   * Create a new JMS sink. When called, this method should call [[RunSink.apply()]]
   * to actually run the [[Sink]].
   */
  def createJmsSink(run: RunSink): Unit
}

object HelloJmsSinkFactory {

  /**
   * This is a function to run a [[Sink]]. It will connect the sink to a
   * stream, materialize the stream and return the sink's materialized value.
   *
   * We use this trait instead of a plain function because we want to have
   * the type parameter on the apply method instead of on the trait.
   */
  trait RunSink {
    def apply[T](sink: Sink[String, T]): T
  }

}