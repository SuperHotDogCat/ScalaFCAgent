package org.scalafcagent.client

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import org.junit.runner.RunWith
import org.scalafcagent.chat.Chat
import org.scalafcagent.chat.Message
import org.scalafcagent.chat.Role
import org.scalafcagent.llm.LLMServer
import org.scalafcagent.tool.SearchTool
import org.scalafcagent.tool.ToolEngine
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ClientSpec extends AnyFunSuite with Matchers {

  private val toolCall: String =
    """<tool>
      |{
      |  "function_name": "search",
      |  "arguments": "{\"query\":\"Scala\",\"limit\":10}"
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

  private def newClient(
    responses: List[String],
    maxToolCalls: Int = 8
  ): IO[(Client, Ref[IO, Vector[Vector[Message]]])] =
    for {
      serverAndRequests <- mockLLMServer(responses)
      server = serverAndRequests._1
      requests = serverAndRequests._2
      client <- Client.create(
        llmServer = server,
        chat = new Chat(new ToolEngine(new SearchTool)),
        maxToolCalls = maxToolCalls
      )
    } yield (client, requests)

  test("Return the model output when no tool is called") {
    val program =
      for {
        clientAndPrompts <- newClient(List("Hello!"))
        client = clientAndPrompts._1
        output <- client.send("hi")
        history <- client.history
      } yield (output, history)

    val (output, history) = program.unsafeRunSync()

    output shouldBe "Hello!"

    history.map(_.role) shouldBe Vector(
      Role.User,
      Role.Assistant
    )
  }

  test("Run tools and send the updated conversation back to the LLMServer") {
    val program =
      for {
        clientAndRequests <- newClient(List(toolCall, "Here you go."))
        client = clientAndRequests._1
        requests = clientAndRequests._2
        output <- client.send("search Scala")
        history <- client.history
        sent <- requests.get
      } yield (output, history, sent)

    val (output, history, sent) = program.unsafeRunSync()

    output shouldBe "Here you go."

    // User -> (Tool呼び出しの)Assistant -> Tool -> (最終的な)Assistant
    history.map(_.role) shouldBe Vector(
      Role.User,
      Role.Assistant,
      Role.Tool,
      Role.Assistant
    )

    // 2回目のリクエストにはToolの結果が含まれている
    sent should have size 2
    val secondRequest = sent(1).map(_.content).mkString("\n")
    secondRequest should include("<tool_result>")
    secondRequest should include("Searching for 'Scala' (limit=10)")
  }

  test("Fail when the tool call limit is exceeded") {
    val program =
      for {
        clientAndRequests <- newClient(List(toolCall, toolCall), maxToolCalls = 2)
        client = clientAndRequests._1
        result <- client.send("loop").attempt
      } yield result

    program.unsafeRunSync().isLeft shouldBe true
  }
}
