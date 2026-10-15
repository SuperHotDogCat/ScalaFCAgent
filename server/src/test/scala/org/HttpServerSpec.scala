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
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
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

  test("Reply to a chat request") {
    val program =
      for {
        mock <- mockLLMServer(List("Hello!"))
        server = mock._1
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(
            post(http.port, "/v1/chat", """{"session":"u1","message":"hi"}""")
          )
        }
      } yield result

    val (status, body) = program.unsafeRunSync()

    status shouldBe 200
    body shouldBe """{"reply":"Hello!"}"""
  }

  test("Run a tool call and return the final output") {
    val program =
      for {
        mock <- mockLLMServer(List(echoCall, "Done"))
        server = mock._1
        requests = mock._2
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(
            post(http.port, "/v1/chat", """{"session":"u1","message":"echo hi"}""")
          )
        }
        sent <- requests.get
      } yield (result, sent)

    val ((status, body), sent) = program.unsafeRunSync()

    status shouldBe 200
    body shouldBe """{"reply":"Done"}"""

    // 2回目のリクエストにToolの結果が含まれている
    sent should have size 2
    val secondRequest = sent(1).map(_.content).mkString("\n")
    secondRequest should include("<tool_result>")
    secondRequest should include("hi")
  }

  test("Keep the conversation per session") {
    val program =
      for {
        mock <- mockLLMServer(List("first", "second"))
        server = mock._1
        requests = mock._2
        _ <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking {
            post(http.port, "/v1/chat", """{"session":"u1","message":"hi"}""")
            post(http.port, "/v1/chat", """{"session":"u1","message":"again"}""")
          }
        }
        sent <- requests.get
      } yield sent

    val sent = program.unsafeRunSync()

    sent should have size 2
    // 2回目のリクエストには1回目の応答が履歴として含まれる
    sent(1).map(_.content).mkString("\n") should include("first")
  }

  test("Separate the conversation per session") {
    val program =
      for {
        mock <- mockLLMServer(List("first", "second"))
        server = mock._1
        requests = mock._2
        _ <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking {
            post(http.port, "/v1/chat", """{"session":"u1","message":"hi"}""")
            post(http.port, "/v1/chat", """{"session":"u2","message":"hi"}""")
          }
        }
        sent <- requests.get
      } yield sent

    val sent = program.unsafeRunSync()

    sent should have size 2
    // 別セッションなので2回目のリクエストに1回目の応答は含まれない
    sent(1).map(_.content).mkString("\n") should not include "first"
  }

  test("Reset a session") {
    val program =
      for {
        mock <- mockLLMServer(List("first", "second"))
        server = mock._1
        requests = mock._2
        _ <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking {
            post(http.port, "/v1/chat", """{"session":"u1","message":"hi"}""")
            post(http.port, "/v1/sessions/u1/reset", "")
            post(http.port, "/v1/chat", """{"session":"u1","message":"hi"}""")
          }
        }
        sent <- requests.get
      } yield sent

    val sent = program.unsafeRunSync()

    sent should have size 2
    // reset後なので2つ目のpromptに1回目の応答は含まれない
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

    val (status, body) = program.unsafeRunSync()

    status shouldBe 200
    body shouldBe """{"status":"ok"}"""
  }

  test("Reject an invalid request") {
    val program =
      for {
        mock <- mockLLMServer(List("Hello!"))
        server = mock._1
        result <- HttpServer.from(server, AgentConfig(), testConfig, tool).use { http =>
          IO.blocking(
            post(http.port, "/v1/chat", "not json")
          )
        }
      } yield result

    val (status, _) = program.unsafeRunSync()

    status shouldBe 400
  }
}
