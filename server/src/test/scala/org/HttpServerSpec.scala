package org.scalafcagent.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.parser.parse
import org.junit.runner.RunWith
import org.scalafcagent.agent.AgentConfig
import org.scalafcagent.chat.Message
import org.scalafcagent.llm.LLMServer
import org.scalafcagent.tool.Schema
import org.scalafcagent.tool.Tool
import org.scalafcagent.tool.ToolResult
import org.scalafcagent.tool.TypedTool
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

// --------------------
// テスト用Tool
// --------------------

case class EchoArguments(
  value: String
)

object EchoSchema extends Schema[EchoArguments] {

  implicit val decoder: Decoder[EchoArguments] =
    deriveDecoder[EchoArguments]

  implicit val encoder: Encoder[EchoArguments] =
    deriveEncoder[EchoArguments]
}

class EchoTool extends TypedTool[EchoArguments] {

  override val name: String =
    "echo"

  override val description: String =
    "Echo the given value"

  override val argumentJSONSchema: String =
    """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}"""

  override val schema: Schema[EchoArguments] =
    EchoSchema

  override def run(args: EchoArguments): IO[ToolResult] =
    IO.pure(ToolResult.Success(args.value))
}


// --------------------
// Spec
// --------------------

@RunWith(classOf[JUnitRunner])
class HttpServerSpec extends AnyFunSuite with Matchers {

  private val client: HttpClient = HttpClient.newHttpClient()

  private val testConfig: HttpServer.Config =
    HttpServer.Config(host = "127.0.0.1", port = 0)

  private val tool: Tool = new EchoTool

  private val echoCall: String =
    """<tool>
      |{
      |  "function_name": "echo",
      |  "arguments": "{\"value\":\"hi\"}"
      |}
      |</tool>
      |""".stripMargin

  // 用意した出力を順番に返し, 受け取ったmessagesを記録するLLMServer
  private def mockLLMServer(
    responses: List[String]
  ): IO[(LLMServer, Ref[IO, Vector[Vector[Message]]])] =
    for {
      queue <- Ref.of[IO, List[String]](responses)
      requests <- Ref.of[IO, Vector[Vector[Message]]](Vector.empty)
    } yield {
      val server = LLMServer { messages =>
        requests.update(_ :+ messages) *>
          queue
            .modify {
              case head :: tail =>
                (tail, IO.pure(head))

              case Nil =>
                (Nil, IO.raiseError[String](new RuntimeException("no more responses")))
            }
            .flatten
      }

      (server, requests)
    }

  private def post(port: Int, path: String, body: String): (Int, String) = {
    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(s"http://127.0.0.1:$port$path"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    val response =
      client.send(request, HttpResponse.BodyHandlers.ofString())

    (response.statusCode(), response.body())
  }

  private def postWithContentType(
    port: Int,
    path: String,
    body: String
  ): (Int, String, String) = {
    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(s"http://127.0.0.1:$port$path"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    val response =
      client.send(request, HttpResponse.BodyHandlers.ofString())

    (
      response.statusCode(),
      response.headers().firstValue("Content-Type").orElse(""),
      response.body()
    )
  }

  private def get(port: Int, path: String): (Int, String) = {
    val request =
      HttpRequest
        .newBuilder()
        .uri(URI.create(s"http://127.0.0.1:$port$path"))
        .GET()
        .build()

    val response =
      client.send(request, HttpResponse.BodyHandlers.ofString())

    (response.statusCode(), response.body())
  }

  private def body(json: Json, field: String): Option[String] =
    json.hcursor.get[String](field).toOption

  private def assistantContent(json: Json): Option[String] =
    json.hcursor
      .downField("choices")
      .downArray
      .downField("message")
      .get[String]("content")
      .toOption

  private def errorMessage(json: Json): Option[String] =
    json.hcursor.downField("error").get[String]("message").toOption

  test("Reply to a chat completion request") {
    val program =
      for {
        mock <- mockLLMServer(List("Hello!"))
        server = mock._1
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(
            post(
              http.port,
              "/v1/chat/completions",
              """{"model":"test-model","messages":[{"role":"user","content":"hi"}]}"""
            )
          )
        }
      } yield result

    val (status, responseBody) = program.unsafeRunSync()
    val json = parse(responseBody).toOption.get

    status shouldBe 200
    body(json, "object") shouldBe Some("chat.completion")
    body(json, "model") shouldBe Some("test-model")
    assistantContent(json) shouldBe Some("Hello!")
  }

  test("Run a tool call and return the final output") {
    val program =
      for {
        mock <- mockLLMServer(List(echoCall, "Done"))
        server = mock._1
        requests = mock._2
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(
            post(
              http.port,
              "/v1/chat/completions",
              """{"messages":[{"role":"user","content":"echo hi"}]}"""
            )
          )
        }
        sent <- requests.get
      } yield (result, sent)

    val ((status, responseBody), sent) = program.unsafeRunSync()

    status shouldBe 200
    assistantContent(parse(responseBody).toOption.get) shouldBe Some("Done")

    // 2回目のリクエストにToolの結果が含まれている
    sent should have size 2
    sent(1).map(_.content).mkString("\n") should include("<tool_result>")
  }

  test("Be stateless and use only the messages in the request") {
    val program =
      for {
        mock <- mockLLMServer(List("first", "second"))
        server = mock._1
        requests = mock._2
        _ <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking {
            post(
              http.port,
              "/v1/chat/completions",
              """{"messages":[{"role":"user","content":"hi"}]}"""
            )
            post(
              http.port,
              "/v1/chat/completions",
              """{"messages":[{"role":"user","content":"again"}]}"""
            )
          }
        }
        sent <- requests.get
      } yield sent

    val sent = program.unsafeRunSync()

    sent should have size 2
    // 状態を持たないので, 2回目に1回目の応答は含まれない
    sent(1).map(_.content).mkString("\n") should not include "first"
  }

  test("Respond to a health check") {
    val program =
      for {
        mock <- mockLLMServer(List("Hello!"))
        server = mock._1
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(get(http.port, "/v1/health"))
        }
      } yield result

    val (status, responseBody) = program.unsafeRunSync()

    status shouldBe 200
    responseBody shouldBe """{"status":"ok"}"""
  }

  test("Reject an invalid request") {
    val program =
      for {
        mock <- mockLLMServer(List("Hello!"))
        server = mock._1
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(
            post(http.port, "/v1/chat/completions", "not json")
          )
        }
      } yield result

    val (status, responseBody) = program.unsafeRunSync()

    status shouldBe 400
    errorMessage(parse(responseBody).toOption.get).isDefined shouldBe true
  }

  test("Reject an unsupported role") {
    val program =
      for {
        mock <- mockLLMServer(List("Hello!"))
        server = mock._1
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(
            post(
              http.port,
              "/v1/chat/completions",
              """{"messages":[{"role":"robot","content":"hi"}]}"""
            )
          )
        }
      } yield result

    val (status, _) = program.unsafeRunSync()

    status shouldBe 400
  }

  test("Stream the reply as server-sent events") {
    val program =
      for {
        mock <- mockLLMServer(List("Hello!"))
        server = mock._1
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(
            postWithContentType(
              http.port,
              "/v1/chat/completions",
              """{"model":"test-model","messages":[{"role":"user","content":"hi"}],"stream":true}"""
            )
          )
        }
      } yield result

    val (status, contentType, responseBody) = program.unsafeRunSync()

    status shouldBe 200
    contentType should include("text/event-stream")

    responseBody should include("\"object\":\"chat.completion.chunk\"")
    responseBody should include("\"model\":\"test-model\"")
    responseBody should include("\"delta\":{\"role\":\"assistant\",\"content\":\"\"}")
    responseBody should include("\"delta\":{\"content\":\"Hello!\"}")
    responseBody should include("\"finish_reason\":\"stop\"")
    responseBody should endWith("data: [DONE]\n\n")
  }

  test("Stream across a tool call as server-sent events") {
    val program =
      for {
        mock <- mockLLMServer(List(echoCall, "Done"))
        server = mock._1
        requests = mock._2
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(
            postWithContentType(
              http.port,
              "/v1/chat/completions",
              """{"messages":[{"role":"user","content":"echo hi"}],"stream":true}"""
            )
          )
        }
        sent <- requests.get
      } yield (result, sent)

    val ((status, _, responseBody), sent) = program.unsafeRunSync()

    status shouldBe 200
    responseBody should include("\"delta\":{\"content\":\"Done\"}")
    responseBody should include("data: [DONE]")

    // Toolの結果が2回目のリクエストに含まれている
    sent should have size 2
    sent(1).map(_.content).mkString("\n") should include("<tool_result>")
  }
}
