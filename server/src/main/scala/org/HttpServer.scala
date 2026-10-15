package org.scalafcagent.server

import java.util.UUID

import scala.util.Failure
import scala.util.Success

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ContentType
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.StandardRoute
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.parser.parse
import io.circe.syntax._
import org.scalafcagent.agent.Agent
import org.scalafcagent.agent.AgentConfig
import org.scalafcagent.chat.Message
import org.scalafcagent.chat.Role
import org.scalafcagent.llm.LLMServer
import org.scalafcagent.llm.LLMServerConfig
import org.scalafcagent.llm.OpenAILLMServer
import org.scalafcagent.tool.Tool

// --------------------
// Request / Response
// --------------------

// OpenAIの Chat Completion リクエストのうち, 使うフィールドだけ
case class RequestMessage(
  role: String,
  content: String
)

case class ChatCompletionRequest(
  model: Option[String] = None,
  messages: Vector[RequestMessage] = Vector.empty,
  stream: Option[Boolean] = None
)

case class StatusResponse(
  status: String
)

object JsonCodecs {

  implicit val requestMessageDecoder: Decoder[RequestMessage] =
    deriveDecoder[RequestMessage]

  implicit val chatCompletionRequestDecoder: Decoder[ChatCompletionRequest] =
    deriveDecoder[ChatCompletionRequest]

  implicit val statusResponseEncoder: Encoder[StatusResponse] =
    deriveEncoder[StatusResponse]
}


// --------------------
// Routes
// --------------------

// OpenAI互換. 状態を持たず, 毎回リクエストのmessagesで応答する (完全ステートレス)
// POST /v1/chat/completions  {"model":...,"messages":[...],"stream":false} -> chat.completion
// POST /v1/chat/completions  {"...","stream":true}                        -> text/event-stream (chat.completion.chunk ... data: [DONE])
// GET  /v1/health            -> {"status":"ok"}
class Routes(
  llmServer: LLMServer,
  agentConfig: AgentConfig,
  model: String,
  tools: Vector[Tool]
) {

  import JsonCodecs._

  def route: Route =
    // LLMの呼び出し (モデルのロードやツールの往復) は20秒を超えることがあるため,
    // Pekkoの既定リクエストタイムアウトを無効化する
    withoutRequestTimeout {
      pathPrefix("v1") {
        concat(
          path("health") {
            get {
              completeJson(StatusCodes.OK, StatusResponse("ok"))
            }
          },
          path("chat" / "completions") {
            post {
              entity(as[String]) { body =>
                decodeRequest(body) match {

                  case Left(error) =>
                    completeJson(
                      StatusCodes.BadRequest,
                      errorJson("invalid_request_error", error)
                    )

                  case Right(request) =>
                    toMessages(request) match {

                      case Left(error) =>
                        completeJson(
                          StatusCodes.BadRequest,
                          errorJson("invalid_request_error", error)
                        )

                      case Right(messages) =>
                        if (request.stream.getOrElse(false)) {
                          complete(streamResponse(request, messages))
                        } else {
                          onComplete(reply(messages).unsafeToFuture()) {

                            case Success(content) =>
                              completeJson(
                                StatusCodes.OK,
                                completionJson(request, content)
                              )

                            case Failure(error) =>
                              completeJson(
                                StatusCodes.InternalServerError,
                                errorJson("server_error", error.getMessage)
                              )
                          }
                        }
                    }
                }
              }
            }
          }
        )
      }
    }

  // --------------------
  // Request
  // --------------------

  private def decodeRequest(
    body: String
  ): Either[String, ChatCompletionRequest] =
    parse(body).flatMap(_.as[ChatCompletionRequest]) match {

      case Left(error) =>
        Left(error.getMessage)

      case Right(request) =>
        Right(request)
    }

  // OpenAIのrole名をこちらのRoleに写像する
  private def roleOf(name: String): Option[Role] =
    name match {

      case "system" | "developer" =>
        Some(Role.System)

      case "user" =>
        Some(Role.User)

      case "assistant" =>
        Some(Role.Assistant)

      case "tool" =>
        Some(Role.Tool)

      case _ =>
        None
    }

  private def toMessages(
    request: ChatCompletionRequest
  ): Either[String, Vector[Message]] =
    request.messages.foldLeft[Either[String, Vector[Message]]](Right(Vector.empty)) {
      (accumulated, message) =>
        for {
          messages <- accumulated
          role <- roleOf(
            message.role
          ).toRight(s"Unsupported role: ${message.role}")
        } yield messages :+ Message(role, message.content)
    }

  // --------------------
  // Agent
  // --------------------

  // ステートレスなのでリクエストごとにAgentを作る (LLMServerは共有)
  private def agent: Resource[IO, Agent] =
    Agent.from(llmServer, agentConfig, tools: _*)

  private def reply(messages: Vector[Message]): IO[String] =
    agent.use(_.send(messages))

  // --------------------
  // Response
  // --------------------

  private def streamResponse(
    request: ChatCompletionRequest,
    messages: Vector[Message]
  ): HttpResponse = {
    val deltas =
      fs2.Stream
        .resource(agent)
        .flatMap(_.sendStream(messages))
        .handleErrorWith(error =>
          fs2.Stream.emit(errorFrame("server_error", error.getMessage))
        )

    HttpResponse(
      status = StatusCodes.OK,
      entity = HttpEntity(
        eventStreamContentType,
        sseSource(chunkStream(request, deltas))
      )
    )
  }

  // OpenAIの chat.completion.chunk をSSEで並べる
  private def chunkStream(
    request: ChatCompletionRequest,
    deltas: fs2.Stream[IO, String]
  ): fs2.Stream[IO, String] = {
    val id = newId()
    val createdAt = now()
    val name = modelName(request)

    def chunk(delta: Json, finish: Json): String =
      sseFrame(
        Json.obj(
          "id" -> id.asJson,
          "object" -> "chat.completion.chunk".asJson,
          "created" -> createdAt.asJson,
          "model" -> name.asJson,
          "choices" -> Json.arr(
            Json.obj(
              "index" -> 0.asJson,
              "delta" -> delta,
              "finish_reason" -> finish
            )
          )
        )
      )

    val role =
      fs2.Stream.emit(
        chunk(
          Json.obj("role" -> "assistant".asJson, "content" -> "".asJson),
          Json.Null
        )
      )

    val content =
      deltas.map(delta => chunk(Json.obj("content" -> delta.asJson), Json.Null))

    val finish =
      fs2.Stream.emit(chunk(Json.obj(), "stop".asJson))

    role ++ content ++ finish ++ fs2.Stream.emit(sseDoneFrame)
  }

  private def completionJson(
    request: ChatCompletionRequest,
    content: String
  ): Json =
    Json.obj(
      "id" -> newId().asJson,
      "object" -> "chat.completion".asJson,
      "created" -> now().asJson,
      "model" -> modelName(request).asJson,
      "choices" -> Json.arr(
        Json.obj(
          "index" -> 0.asJson,
          "message" -> Json.obj(
            "role" -> "assistant".asJson,
            "content" -> content.asJson
          ),
          "finish_reason" -> "stop".asJson
        )
      )
    )

  private def newId(): String =
    "chatcmpl-" + UUID.randomUUID().toString.replace("-", "")

  private def now(): Long =
    System.currentTimeMillis() / 1000

  private def modelName(request: ChatCompletionRequest): String =
    request.model.filter(_.nonEmpty).getOrElse(model)

  private def errorJson(typ: String, message: String): Json =
    Json.obj(
      "error" -> Json.obj(
        "message" -> message.asJson,
        "type" -> typ.asJson
      )
    )

  private def errorFrame(typ: String, message: String): String =
    sseFrame(errorJson(typ, message))

  // fs2の差分のStreamをPekkoのSourceに橋渡しする
  // Sourceがmaterializeされた時点でfs2のStreamを走らせ, 差分をqueueに積む
  private def sseSource(
    frames: fs2.Stream[IO, String]
  ): Source[ByteString, NotUsed] =
    Source
      .queue[String](16, OverflowStrategy.backpressure)
      .map(ByteString(_))
      .mapMaterializedValue { queue =>
        frames
          .evalMap(frame => IO.fromFuture(IO(queue.offer(frame))).void)
          .compile
          .drain
          // クライアント切断などでqueueが閉じた場合は止める
          .handleError(_ => ())
          .guarantee(IO(queue.complete()))
          .unsafeRunAndForget()

        NotUsed
      }

  private def sseFrame(json: Json): String =
    s"data: ${json.noSpaces}\n\n"

  private val sseDoneFrame: String =
    "data: [DONE]\n\n"

  private val eventStreamContentType: ContentType =
    ContentType.parse("text/event-stream") match {
      case Right(contentType) => contentType
      case Left(_) => ContentTypes.`text/plain(UTF-8)`
    }

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
      .flatMap(llmServer =>
        start(llmServer, agentConfig, config, llm.model, tools: _*)
      )

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
    start(llmServer, agentConfig, config, "", tools: _*)

  private def start(
    llmServer: LLMServer,
    agentConfig: AgentConfig,
    config: Config,
    model: String,
    tools: Tool*
  ): Resource[IO, HttpServer] =
    for {
      // LLMServerは全リクエストで共有する
      system <- Resource.make(
        IO(ActorSystem("scalafcagent-http"))
      )(system => IO.fromFuture(IO(system.terminate())).void)

      routes = new Routes(llmServer, agentConfig, model, tools.toVector)

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
