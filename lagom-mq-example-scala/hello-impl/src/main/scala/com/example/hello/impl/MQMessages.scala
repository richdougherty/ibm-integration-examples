package com.example.hello.impl

import play.api.libs.json.{Format, Json}

case class UpdateGreetingMessage(id: String, message: String)

object UpdateGreetingMessage {
  implicit val format: Format[UpdateGreetingMessage] = Json.format
}
