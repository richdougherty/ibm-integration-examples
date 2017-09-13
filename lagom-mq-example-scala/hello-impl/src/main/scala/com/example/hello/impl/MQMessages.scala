package com.example.hello.impl

import play.api.libs.json.{Format, Json}

case class UseGreetingMessageForId(id: String, message: String)

object UseGreetingMessageForId {
  implicit val format: Format[UseGreetingMessageForId] = Json.format
}
