package org.bitcoins.server.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}

import scala.util.Try

trait ServerRoute {
  def handleCommand: PartialFunction[ServerCommand, Route]

  def withValidServerCommand[R](validator: Try[R]): Directive1[R] =
    validator.fold(
      e => complete(Server.httpBadRequest(e)),
      provide
    )
}
