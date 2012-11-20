package com.giltgroupe.util.gatling.websocket

import akka.actor.{Props, ActorRef}
import com.excilys.ebi.gatling.core.action.builder.ActionBuilder
import com.excilys.ebi.gatling.core.action.{Action, BaseActor, Bypass, system}
import com.excilys.ebi.gatling.core.config.ProtocolConfigurationRegistry
import com.excilys.ebi.gatling.core.result.message.RequestStatus._
import com.excilys.ebi.gatling.core.result.writer.DataWriter
import com.excilys.ebi.gatling.core.session.{EvaluatableString, Session}
import com.excilys.ebi.gatling.core.util.StringHelper._
import com.excilys.ebi.gatling.http.ahc.GatlingHttpClient
import com.ning.http.client.websocket.{WebSocket, WebSocketListener, WebSocketTextListener, WebSocketUpgradeHandler}
import grizzled.slf4j.Logging
import java.io.IOException
import java.net.URI

object Predef {
  /**
   * References a web socket that can have actions performed on it.
   *
   * @param attributeName The name of the session attribute that stores the socket
   */
  def websocket(attributeName: String) = new WebSocketBaseBuilder(attributeName)

  /** The default AsyncHttpClient WebSocket client. */
  implicit object WebSocketClient extends WebSocketClient with Logging {
    def open(uri: URI, listener: WebSocketListener) {
      GatlingHttpClient.client.prepareGet(uri.toString).execute(
        new WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build()
      )
    }
  }

  /** The default Gatling request logger. */
  implicit object RequestLogger extends RequestLogger {
    def logRequest(session: Session, actionName: String, requestStatus: RequestStatus, started: Long, ended: Long, errorMessage: Option[String]) {
      DataWriter.logRequest(session.scenarioName, session.userId, actionName,
        started, ended, ended, ended,
        requestStatus, errorMessage)
    }
  }
}

trait WebSocketClient {
  @throws(classOf[IOException])
  def open(uri: URI, listener: WebSocketListener)
}

trait RequestLogger {
  def logRequest(session: Session, actionName: String, requestStatus: RequestStatus, started: Long, ended: Long, errorMessage: Option[String] = None)
}

class WebSocketBaseBuilder(val attributeName: String) {
  /**
   * Opens a web socket and stores it in the session.
   *
   * @param fUrl The socket URL
   * @param actionName The action name in the log
   */
  def open(fUrl: EvaluatableString, actionName: String = attributeName)(implicit webSocketClient: WebSocketClient, requestLogger: RequestLogger) = new OpenWebSocketActionBuilder(attributeName, actionName, fUrl, webSocketClient, requestLogger)

  /**
   * Sends a message on the given socket.
   *
   * @param fMessage The message
   * @param actionName The action name in the log
   */
  def sendMessage(fMessage: EvaluatableString, actionName: String = attributeName) = new SendWebSocketMessageActionBuilder(attributeName, actionName, fMessage)

  /**
   * Closes a web socket.
   *
   * @param actionName The action name in the log
   */
  def close(actionName: String = attributeName) = new CloseWebSocketActionBuilder(attributeName, actionName)
}

class OpenWebSocketActionBuilder(val attributeName: String, val actionName: String, val fUrl: EvaluatableString, val webSocketClient: WebSocketClient, val requestLogger: RequestLogger, val next: ActorRef = null) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder = new OpenWebSocketActionBuilder(attributeName, actionName, fUrl, webSocketClient, requestLogger, next)

  def build(registry: ProtocolConfigurationRegistry): ActorRef = system.actorOf(Props(new OpenWebSocketAction(attributeName, actionName, fUrl, webSocketClient, requestLogger, next, registry)))
}

class SendWebSocketMessageActionBuilder(val attributeName: String, val actionName: String, val fMessage: EvaluatableString, val next: ActorRef = null) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder = new SendWebSocketMessageActionBuilder(attributeName, actionName, fMessage, next)

  def build(registry: ProtocolConfigurationRegistry): ActorRef = system.actorOf(Props(new SendWebSocketMessageAction(attributeName, actionName, fMessage, next, registry)))
}

class CloseWebSocketActionBuilder(val attributeName: String, val actionName: String, val next: ActorRef = null) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder = new CloseWebSocketActionBuilder(attributeName, actionName, next)

  def build(registry: ProtocolConfigurationRegistry): ActorRef = system.actorOf(Props(new CloseWebSocketAction(attributeName, actionName, next, registry)))
}

private[websocket] class OpenWebSocketAction(attributeName: String, actionName: String, fUrl: EvaluatableString, webSocketClient: WebSocketClient, requestLogger: RequestLogger, next: ActorRef, registry: ProtocolConfigurationRegistry) extends Action(attributeName, next) with Bypass {
  def execute(session: Session) {
    info("Opening websocket '" + attributeName + "': Scenario '" + session.scenarioName + "', UserId #" + session.userId)

    val actor = context.actorOf(Props(new WebSocketActor(attributeName, requestLogger)))

    val started = System.currentTimeMillis
    try {
      webSocketClient.open(URI.create(fUrl(session)), new WebSocketTextListener {
        var opened = false

        def onOpen(webSocket: WebSocket) {
          opened = true
          actor ! OnOpen(actionName, webSocket, started, System.currentTimeMillis, next, session)
        }

        def onMessage(message: String) {
          actor ! OnMessage(message)
        }

        def onFragment(fragment: String, last: Boolean) {
        }

        def onClose(webSocket: WebSocket) {
          if (opened) {
            actor ! OnClose()
          }
          else {
            actor ! OnFailedOpen(actionName, "closed", started, System.currentTimeMillis, next, session)
          }
        }

        def onError(t: Throwable) {
          if (opened) {
            actor ! OnError(t)
          }
          else {
            actor ! OnFailedOpen(actionName, t.getMessage, started, System.currentTimeMillis, next, session)
          }
        }
      })
    }
    catch {
      case e: IOException =>
        actor ! OnFailedOpen(actionName, e.getMessage, started, System.currentTimeMillis, next, session)
    }
  }
}

private[websocket] class SendWebSocketMessageAction(attributeName: String, actionName: String, fMessage: EvaluatableString, next: ActorRef, registry: ProtocolConfigurationRegistry) extends Action(attributeName, next) with Bypass {
  def execute(session: Session) {
    session.getAttributeAsOption[(ActorRef, _)](attributeName).foreach(_._1 ! SendMessage(actionName, fMessage(session), next, session))
  }
}

private[websocket] class CloseWebSocketAction(attributeName: String, actionName: String, next: ActorRef, registry: ProtocolConfigurationRegistry) extends Action(attributeName, next) with Bypass {
  def execute(session: Session) {
    info("Closing websocket '" + attributeName + "': Scenario '" + session.scenarioName + "', UserId #" + session.userId)
    session.getAttributeAsOption[(ActorRef, _)](attributeName).foreach(_._1 ! Close(actionName, next, session))
  }
}

private[websocket] class WebSocketActor(val attributeName: String, requestLogger: RequestLogger) extends BaseActor {
  var webSocket: Option[WebSocket] = None
  var errorMessage: Option[String] = None

  def receive = {
    case OnOpen(actionName, webSocket, started, ended, next, session) =>
      requestLogger.logRequest(session, actionName, OK, started, ended)
      this.webSocket = Some(webSocket)
      next ! session.setAttribute(attributeName, (self, webSocket))

    case OnFailedOpen(actionName, message, started, ended, next, session) =>
      warn("Websocket '" + attributeName + "' failed to open: " + message)
      requestLogger.logRequest(session, actionName, KO, started, ended, Some(message))
      next ! session.setFailed
      context.stop(self)

    case OnMessage(message) =>
      if (isDebugEnabled) debug("Received message on websocket '" + attributeName + "':" + END_OF_LINE + message)

    case OnClose() =>
      errorMessage = Some("Websocket '" + attributeName + "' was unexpectedly closed")
      warn(errorMessage.get)

    case OnError(t) =>
      errorMessage = Some("Websocket '" + attributeName + "' gave an error: '" + t.getMessage + "'")
      warn(errorMessage.get)

    case SendMessage(actionName, message, next, session) =>
      if (!handleEarlierError(actionName, next, session)) {
        val started = System.currentTimeMillis
        webSocket.foreach(_.sendTextMessage(message))
        requestLogger.logRequest(session, actionName, OK, started, System.currentTimeMillis)
        next ! session
      }

    case Close(actionName, next, session) =>
      if (!handleEarlierError(actionName, next, session)) {
        val started = System.currentTimeMillis
        webSocket.foreach(_.close)
        requestLogger.logRequest(session, actionName, OK, started, System.currentTimeMillis)
        next ! session
        context.stop(self)
      }
  }

  def handleEarlierError(actionName: String, next: ActorRef, session: Session) = {
    if (errorMessage.isDefined) {
      val now = System.currentTimeMillis
      requestLogger.logRequest(session, actionName, KO, now, now, errorMessage)
      errorMessage = None
      next ! session.setFailed
      context.stop(self)
      true
    }
    else {
      false
    }
  }
}

private[websocket] case class OnOpen(actionName: String, webSocket: WebSocket, started: Long, ended: Long, next: ActorRef, session: Session)
private[websocket] case class OnFailedOpen(actionName: String, message: String, started: Long, ended: Long, next: ActorRef, session: Session)
private[websocket] case class OnMessage(message: String)
private[websocket] case class OnClose()
private[websocket] case class OnError(t: Throwable)

private[websocket] case class SendMessage(actionName: String, message: String, next: ActorRef, session: Session)
private[websocket] case class Close(actionName: String, next: ActorRef, session: Session)
