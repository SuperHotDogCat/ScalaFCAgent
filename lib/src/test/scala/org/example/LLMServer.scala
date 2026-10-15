package org.scalafcagent.llm

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import io.circe.parser.parse
import org.junit.runner.RunWith
import org.scalafcagent.chat.Message
import org.scalafcagent.chat.Role
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

// リクエストを1回受け取り, 記録した上でOpenAI互換のレスポンスを返すスタブサーバ
// JDK内蔵のHttpServerを使うので追加の依存は要らない
@RunWith(classOf[JUnitRunner])
class OpenAILLMServerSpec extends AnyFunSuite with Matchers {

  private def withStubServer[A](
    responseBody: String,
    status: Int = 200
  )(use: String => IO[A]): (A, String, String) = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

    val capturedBody = new AtomicReference[String]("")
    val capturedPath = new AtomicReference[String]("")

    server.createContext(
      "/v1/chat/completions",
      exchange => {
        val body =
          new String(
            exchange.getRequestBody.readAllBytes(),
            StandardCharsets.UTF_8
          )

        capturedBody.set(body)
        capturedPath.set(exchange.getRequestURI.getPath)

        val bytes = responseBody.getBytes(StandardCharsets.UTF_8)

        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )

    server.start()

    val baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}"

    try {
      val result = use(baseUrl).unsafeRunSync()
      (result, capturedBody.get, capturedPath.get)
    } finally {
      server.stop(0)
    }
  }

  private def newServer(baseUrl: String): OpenAILLMServer =
    new OpenAILLMServer(
      LLMServerConfig(
        baseUrl = baseUrl,
        model = "test-model",
        timeout = Duration.ofSeconds(5)
      )
    )

  private def messages(json: io.circe.Json): Vector[io.circe.Json] =
    json.hcursor.downField("messages").values.getOrElse(Vector.empty).toVector

  private def field(json: io.circe.Json, name: String): Option[String] =
    json.hcursor.get[String](name).toOption

  test("Send the conversation as role-separated OpenAI messages") {
    val response =
      """{"choices":[{"message":{"role":"assistant","content":"Hello!"}}]}"""

    val (content, body, path) =
      withStubServer(response) { baseUrl =>
        newServer(baseUrl).complete(
          Vector(
            Message(Role.System, "You are helpful."),
            Message(Role.User, "hi"),
            Message(Role.Tool, "<tool_result>\nok\n</tool_result>"),
            Message(Role.Thinking, "hmm")
          )
        )
      }

    content shouldBe "Hello!"
    path shouldBe "/v1/chat/completions"

    val json = parse(body).toOption.get

    field(json, "model") shouldBe Some("test-model")
    json.hcursor.get[Boolean]("stream").toOption shouldBe Some(false)

    val sent = messages(json)

    // System -> system, User -> user, Tool -> user, Thinking -> assistant
    sent.map(message => field(message, "role")) shouldBe Vector(
      Some("system"),
      Some("user"),
      Some("user"),
      Some("assistant")
    )

    // contentはそのまま渡す (タグ付けはサーバのチャットテンプレートに任せる)
    sent.map(message => field(message, "content")) shouldBe Vector(
      Some("You are helpful."),
      Some("hi"),
      Some("<tool_result>\nok\n</tool_result>"),
      Some("hmm")
    )
  }

  test("Send no system message when the conversation has none") {
    val response =
      """{"choices":[{"message":{"role":"assistant","content":"Hi!"}}]}"""

    val (_, body, _) =
      withStubServer(response) { baseUrl =>
        newServer(baseUrl).complete(Vector(Message(Role.User, "hello")))
      }

    val sent = messages(parse(body).toOption.get)

    sent should have size 1
    sent.head.hcursor.get[String]("role").toOption shouldBe Some("user")
  }

  test("Fail on a non-2xx response") {
    val (result, _, _) =
      withStubServer("""{"error":"boom"}""", status = 500) { baseUrl =>
        newServer(baseUrl).complete(Vector(Message(Role.User, "hi"))).attempt
      }

    result.isLeft shouldBe true
  }
}
