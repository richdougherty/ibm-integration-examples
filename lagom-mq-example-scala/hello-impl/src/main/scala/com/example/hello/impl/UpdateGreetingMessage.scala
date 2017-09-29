package com.example.hello.impl

import play.api.libs.json.{Format, Json}

/**
 * An internal message that is sent over JMS. This message
 * is generated when a [[HelloCommand]] [[UseGreetingMessage]]
 * is generated.
 *
 * @param id
 * @param message
 */
case class UpdateGreetingMessage(id: String, message: String)

object UpdateGreetingMessage {
  // Create the format logic using the Json.format macro
  implicit val format: Format[UpdateGreetingMessage] = Json.format
}
