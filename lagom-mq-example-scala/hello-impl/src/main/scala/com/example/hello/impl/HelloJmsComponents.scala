package com.example.hello.impl

import akka.stream.scaladsl.{Sink, Source}
import com.example.hello.impl.HelloJmsComponents.{RunSink, RunSource}

trait HelloJmsComponents {
  def helloJmsSource: HelloJmsSourceFactory
  def helloJmsSink: HelloJmsSinkFactory
}

object HelloJmsComponents {

  trait RunSource {
    def apply[T](source: Source[String, T]): T
  }

  trait RunSink {
    def apply[T](sink: Sink[String, T]): T
  }

}

trait HelloJmsSourceFactory {
  def createJmsSource(run: RunSource): Unit
}

trait HelloJmsSinkFactory {
  def createJmsSink(run: RunSink): Unit
}

