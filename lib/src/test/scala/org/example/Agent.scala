package org.scalafcagent.agent

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import org.junit.runner.RunWith
import org.scalafcagent.chat.Message
import org.scalafcagent.chat.Role
import org.scalafcagent.llm.LLMServer
import org.scalafcagent.llm.LLMServerConfig
import org.scalafcagent.tool.SearchTool
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class AgentSpec extends AnyFunSuite with Matchers {

  private val toolCall: String =
    """<tool>
      |{
      |  "function_name": "search",
      |  "arguments": "{\"query\":\"Scala\",\"limit\":10}"
      |}
      |</tool>
      |""".stripMargin

  // 用意した出力を順番に返すLLMServer
  private def mockLLMServer(responses: List[String]): IO[LLMServer] =
    Ref.of[IO, List[String]](responses).map { queue =>
      LLMServer { _ =>
        queue
          .modify {
            case head :: tail =>
              (tail, IO.pure(head))

            case Nil =>
              (Nil, IO.raiseError[String](new RuntimeException("no more responses")))
          }
          .flatten
      }
    }

  test("Create an Agent from a configuration and tools") {
    val program =
      for {
        server <- mockLLMServer(List("Hello!"))
        output <- Agent.from(server, AgentConfig(), new SearchTool).use(_.send("hi"))
      } yield output

    program.unsafeRunSync() shouldBe "Hello!"
  }

  test("Apply the system prompt as the first message") {
    val program =
      for {
        server <- mockLLMServer(List("Hello!"))
        history <- Agent
          .from(
            server,
            AgentConfig(systemPrompt = Some("You are helpful.")),
            new SearchTool
          )
          .use(agent => agent.send("hi") *> agent.history)
      } yield history

    program.unsafeRunSync().map(_.role) shouldBe Vector(
      Role.System,
      Role.User,
      Role.Assistant
    )
  }

  test("Add the tool instructions to the system prompt") {
    val program =
      for {
        server <- mockLLMServer(List("Hello!"))
        history <- Agent.from(server, AgentConfig(), new SearchTool).use(_.history)
      } yield history

    val system = program.unsafeRunSync().head

    system.role shouldBe Role.System
    system.content should include("## Available Tools")
    system.content should include("search")
    system.content should include("Search the web")
    system.content should include("query")
    system.content should include("object")
  }

  test("Combine the user system prompt with the tool instructions") {
    val program =
      for {
        server <- mockLLMServer(List("Hello!"))
        history <- Agent
          .from(
            server,
            AgentConfig(systemPrompt = Some("You are helpful.")),
            new SearchTool
          )
          .use(_.history)
      } yield history

    val system = program.unsafeRunSync().head

    system.content should startWith("You are helpful.")
    system.content should include("## Available Tools")
  }

  test("Run tools and return the final output") {
    val program =
      for {
        server <- mockLLMServer(List(toolCall, "Here you go."))
        output <- Agent.from(server, AgentConfig(), new SearchTool).use(_.send("search"))
      } yield output

    program.unsafeRunSync() shouldBe "Here you go."
  }

  test("Reset the conversation") {
    val program =
      for {
        server <- mockLLMServer(List("Hello!"))
        history <- Agent.from(server, AgentConfig(), new SearchTool).use { agent =>
          agent.send("hi") *> agent.reset *> agent.history
        }
      } yield history

    val history = program.unsafeRunSync()

    history.map(_.role) shouldBe Vector(Role.System)
    history.head.content should include("## Available Tools")
  }

  test("Send full messages and keep the tool instructions") {
    val program =
      for {
        server <- mockLLMServer(List("Hello!"))
        history <- Agent
          .from(server, AgentConfig(), new SearchTool)
          .use(agent => agent.send(Vector(Message(Role.User, "hi"))) *> agent.history)
      } yield history

    val messages = program.unsafeRunSync()

    // 初期Chat (Toolの使い方のsystem) の後ろにmessagesが載る
    messages.map(_.role) shouldBe Vector(Role.System, Role.User, Role.Assistant)
    messages.head.content should include("## Available Tools")
  }

  test("Create builds an OpenAI-compatible Agent from LLMServerConfig") {
    val llm = LLMServerConfig(
      baseUrl = "http://localhost:9999",
      model = "test-model"
    )

    val program =
      Agent.create(llm, AgentConfig(), new SearchTool).use(_.history)

    program.unsafeRunSync().map(_.role) shouldBe Vector(Role.System)
  }
}
