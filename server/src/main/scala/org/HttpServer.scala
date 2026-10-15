package org.scalafcagent.server

import scala.util.Failure
import scala.util.Success

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.PathMatchers.Segment
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.StandardRoute
import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.parser.parse
import io.circe.syntax._
import org.scalafcagent.agent.Agent
import org.scalafcagent.agent.AgentConfig
import org.scalafcagent.llm.LLMServer
import org.scalafcagent.llm.LLMServerConfig
import org.scalafcagent.llm.OpenAILLMServer
import org.scalafcagent.tool.Tool

// --------------------
// Request / Response
// --------------------

case class ChatRequest(
  session: String,
  message: String
)

case class ChatResponse(
  reply: String
)

case class ErrorResponse(
  error: String
)

case class StatusResponse(
  status: String
)

object JsonCodecs {

  implicit val chatRequestDecoder: Decoder[ChatRequest] =
    deriveDecoder[ChatRequest]

  implicit val chatResponseEncoder: Encoder[ChatResponse] =
    deriveEncoder[ChatResponse]

  implicit val errorResponseEncoder: Encoder[ErrorResponse] =
    deriveEncoder[ErrorResponse]

  implicit val statusResponseEncoder: Encoder[StatusResponse] =
    deriveEncoder[StatusResponse]
}


// --------------------
// Routes
// --------------------

// POST /v1/chat                 {"session":"u1","message":"..."} -> {"reply":"..."}
// POST /v1/sessions/{id}/reset  -> {"status":"ok"}
// GET  /v1/health               -> {"status":"ok"}
class Routes(
  sessions: SessionStore
) {

  import JsonCodecs._

  def route: Route =
    pathPrefix("v1") {
      concat(
        path("health") {
          get {
            completeJson(StatusCodes.OK, StatusResponse("ok"))
          }
        },
        path("chat") {
          post {
            entity(as[String]) { body =>
              parse(body).flatMap(_.as[ChatRequest]) match {

                case Left(error) =>
                  completeJson(
                    StatusCodes.BadRequest,
                    ErrorResponse(error.getMessage)
                  )

                case Right(request) =>
                  onComplete(handle(request).unsafeToFuture()) {

                    case Success(response) =>
                      completeJson(StatusCodes.OK, response)

                    case Failure(error) =>
                      completeJson(
                        StatusCodes.InternalServerError,
                        ErrorResponse(error.getMessage)
                      )
                  }
              }
            }
          }
        },
        path("sessions" / Segment / "reset") { sessionId =>
          post {
            onComplete(sessions.remove(sessionId).unsafeToFuture()) {

              case Success(_) =>
                completeJson(StatusCodes.OK, StatusResponse("ok"))

              case Failure(error) =>
                completeJson(
                  StatusCodes.InternalServerError,
                  ErrorResponse(error.getMessage)
                )
            }
          }
        }
      )
    }

  private def handle(request: ChatRequest): IO[ChatResponse] =
    sessions
      .getOrCreate(request.session)
      .flatMap(_.send(request.message))
      .map(ChatResponse(_))

  private def completeJson[A](
    status: StatusCode,
    value: A
  )(implicit encoder: Encoder[A]): StandardRoute =
    complete(
      HttpResponse(
        status = status,
        entity = HttpEntity(ContentTypes.`application/json`, value.asJson.noSpaces)
      )
    )
}


// --------------------
// HttpServer
// --------------------

class HttpServer(
  val binding: Http.ServerBinding
) {

  def host: String =
    binding.localAddress.getHostString

  def port: Int =
    binding.localAddress.getPort
}


object HttpServer {

  case class Config(
    host: String = "127.0.0.1",
    port: Int = 8080
  )

  // 設定とToolからHTTPサーバを立てる
  def resource(
    llm: LLMServerConfig,
    tools: Tool*
  ): Resource[IO, HttpServer] =
    resource(llm, AgentConfig(), Config(), tools: _*)

  def resource(
    llm: LLMServerConfig,
    agentConfig: AgentConfig,
    config: Config,
    tools: Tool*
  ): Resource[IO, HttpServer] =
    OpenAILLMServer
      .resource(llm)
      .flatMap(llmServer => from(llmServer, agentConfig, config, tools: _*))

  // LLMServerを差し替えて立てる (テスト用)
  def from(
    llmServer: LLMServer,
    tools: Tool*
  ): Resource[IO, HttpServer] =
    from(llmServer, AgentConfig(), Config(), tools: _*)

  def from(
    llmServer: LLMServer,
    agentConfig: AgentConfig,
    config: Config,
    tools: Tool*
  ): Resource[IO, HttpServer] =
    for {
      // LLMServerは全セッションで共有する
      sessions <- Resource.eval(
        SessionStore.inMemory(
          Agent.from(llmServer, agentConfig, tools: _*).use(IO.pure)
        )
      )

      system <- Resource.make(
        IO(ActorSystem("scalafcagent-http"))
      )(system => IO.fromFuture(IO(system.terminate())).void)

      routes = new Routes(sessions)

      binding <- Resource.make(
        IO.fromFuture(
          IO(
            Http(system)
              .newServerAt(config.host, config.port)
              .bind(Route.toFunction(routes.route)(system))
          )
        )
      )(binding => IO.fromFuture(IO(binding.unbind())).void)
    } yield new HttpServer(binding)
}
