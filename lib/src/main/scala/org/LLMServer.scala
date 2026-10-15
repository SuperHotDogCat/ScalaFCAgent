package org.scalafcagent.llm

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.stream.Collectors

import scala.jdk.CollectionConverters._

import cats.effect.IO
import cats.effect.Resource
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.scalafcagent.chat.Message
import org.scalafcagent.chat.Role

// --------------------
// LLMServer
// --------------------

// 外部 (vLLM, ollamaなど) のOpenAI互換APIを想定する
// 会話はRole付きのmessagesとして渡す. ロールごとのタグ付け (chat template) は
// 受け取ったOpenAI互換サーバが行うため, こちらでは行わない
trait LLMServer {

  def complete(messages: Vector[Message]): IO[String]

  // ストリーミング. 本文の差分を到着順に流す
  // デフォルトはcompleteを1つの差分として返す (ストリーミング未対応の実装向け)
  def stream(messages: Vector[Message]): Stream[IO, String] =
    Stream.eval(complete(messages))
}

object LLMServer {

  // テストやモック用
  def apply(run: Vector[Message] => IO[String]): LLMServer =
    new LLMServer {

      def complete(messages: Vector[Message]): IO[String] =
        run(messages)
    }
}


// --------------------
// Config
// --------------------

case class LLMServerConfig(
  baseUrl: String,
  model: String,
  apiKey: Option[String] = None,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  timeout: Duration = Duration.ofSeconds(120)
)


// --------------------
// OpenAI互換
// --------------------

class OpenAILLMServer(
  val config: LLMServerConfig,
  val httpClient: HttpClient = HttpClient.newHttpClient(),
  // RoleをOpenAI互換APIのrole名に写像する
  val roleName: Role => String = OpenAILLMServer.defaultRoleName
) extends LLMServer {

  def complete(messages: Vector[Message]): IO[String] =
    IO.blocking {
      val response =
        httpClient.send(
          request(messages, stream = false),
          HttpResponse.BodyHandlers.ofString()
        )

      if (response.statusCode() / 100 != 2) {
        throw new RuntimeException(
          s"LLMServer error ${response.statusCode()}: ${response.body()}"
        )
      } else {
        parseContent(response.body())
      }
    }

  // OpenAI互換のストリーミング (SSE) を, 本文の差分のStreamとして返す
  override def stream(messages: Vector[Message]): Stream[IO, String] =
    Stream
      .bracket(IO.blocking(openStream(messages)))(response =>
        IO(response.body().close())
      )
      .flatMap { response =>
        if (response.statusCode() / 100 != 2) {
          Stream
            .eval(IO.blocking(response.body().collect(Collectors.joining("\n"))))
            .flatMap { body =>
              Stream.raiseError[IO](
                new RuntimeException(
                  s"LLMServer error ${response.statusCode()}: $body"
                )
              )
            }
        } else {
          // SSEを1行ずつ読みながら差分を取り出す
          Stream
            .fromBlockingIterator[IO](response.body().iterator().asScala, 1)
            .flatMap(line => Stream.fromOption(parseDelta(line)))
        }
      }

  private def openStream(
    messages: Vector[Message]
  ): HttpResponse[java.util.stream.Stream[String]] =
    httpClient.send(
      request(messages, stream = true),
      HttpResponse.BodyHandlers.ofLines()
    )

  private def request(messages: Vector[Message], stream: Boolean): HttpRequest = {
    val builder =
      HttpRequest
        .newBuilder()
        .uri(URI.create(endpoint))
        .timeout(config.timeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body(messages, stream)))

    config.apiKey.foreach { key =>
      builder.header("Authorization", s"Bearer $key")
    }

    builder.build()
  }

  private def endpoint: String =
    config.baseUrl.stripSuffix("/") + "/v1/chat/completions"

  private def body(messages: Vector[Message], stream: Boolean): String = {
    val fields =
      List(
        "model" -> config.model.asJson,
        "messages" -> messagesJson(messages),
        "stream" -> stream.asJson
      ) ++
        config.temperature.map(value => "temperature" -> value.asJson) ++
        config.maxTokens.map(value => "max_tokens" -> value.asJson)

    Json.obj(fields: _*).noSpaces
  }

  // Role付きのmessagesをOpenAI互換のmessagesに変換する
  // これによりOpenAI互換サーバ側がモデル固有のチャットテンプレートを適用する
  private def messagesJson(messages: Vector[Message]): Json =
    Json.arr(
      messages.map { message =>
        Json.obj(
          "role" -> roleName(message.role).asJson,
          "content" -> message.content.asJson
        )
      }: _*
    )

  private def parseContent(body: String): String =
    parse(body).flatMap { json =>
      json.hcursor
        .downField("choices")
        .downArray
        .downField("message")
        .get[String]("content")
    } match {

      case Right(content) =>
        content

      case Left(error) =>
        throw new RuntimeException(
          s"Unexpected LLMServer response: ${error.getMessage}: $body"
        )
    }

  // SSEの1行 ("data: {...}" または "data: [DONE]") から本文の差分を取り出す
  private def parseDelta(line: String): Option[String] =
    if (!line.startsWith("data:")) {
      None
    } else {
      val payload = line.stripPrefix("data:").trim

      if (payload.isEmpty || payload == "[DONE]") {
        None
      } else {
        parse(payload).toOption
          .flatMap { json =>
            json.hcursor
              .downField("choices")
              .downArray
              .downField("delta")
              .get[String]("content")
              .toOption
          }
          .filter(_.nonEmpty)
      }
    }
}


object OpenAILLMServer {

  // OpenAI互換APIのrole名に写像する
  // Thinkingは専用のroleが無いのでassistantに寄せる
  // Toolの結果はネイティブのtools APIを使わないのでtool_call_idが不要なuserに載せる
  def defaultRoleName(role: Role): String =
    role match {

      case Role.System =>
        "system"

      case Role.User =>
        "user"

      case Role.Assistant =>
        "assistant"

      case Role.Thinking =>
        "assistant"

      case Role.Tool =>
        "user"
    }

  // HttpClientを閉じるResource
  def resource(config: LLMServerConfig): Resource[IO, OpenAILLMServer] =
    Resource.make(
      IO(new OpenAILLMServer(config))
    ) { server =>
      IO(server.httpClient.close())
    }
}
