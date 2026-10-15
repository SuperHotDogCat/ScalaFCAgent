package org.scalafcagent.chat

import cats.effect.unsafe.implicits.global
import org.junit.runner.RunWith
import org.scalafcagent.tool.SearchTool
import org.scalafcagent.tool.ToolEngine
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ChatSpec extends AnyFunSuite with Matchers {

  private val chat = new Chat(
    new ToolEngine(new SearchTool)
  )

  test("Assign messages to roles") {
    val result =
      chat
        .system("You are a hotel booking assistant.")
        .user("Find a hotel in Tokyo.")

    result.messages should have size 2

    result.messages.head shouldBe Message(
      Role.System,
      "You are a hotel booking assistant."
    )

    result.messages(1).role shouldBe Role.User
  }

  test("Keep the conversation as role-separated messages") {
    val result =
      chat
        .system("system prompt")
        .user("hello")
        .assistant("hi")

    result.messages.map(_.role) shouldBe Vector(
      Role.System,
      Role.User,
      Role.Assistant
    )

    result.messages.map(_.content) shouldBe Vector(
      "system prompt",
      "hello",
      "hi"
    )
  }

  test("Parse tool calls from the model output") {
    val output =
      """I will search.
        |<tool>
        |{
        |  "function_name": "search",
        |  "arguments": "{\"query\":\"Scala\",\"limit\":10}"
        |}
        |</tool>
        |""".stripMargin

    val result = chat.toolCalls(output)

    result should have size 1
    result.head.functionName shouldBe "search"
    result.head.arguments shouldBe """{"query":"Scala","limit":10}"""
  }

  test("Ignore output without a tool block") {
    chat.toolCalls("No tool here.") shouldBe empty
  }

  test("Parse a tool call whose arguments are a JSON object") {
    val output =
      """<tool>
        |{
        |  "function_name": "search",
        |  "arguments": {"query": "Scala", "limit": 10}
        |}
        |</tool>
        |""".stripMargin

    val result = chat.toolCalls(output)

    result should have size 1
    result.head.functionName shouldBe "search"
    result.head.arguments shouldBe """{"query":"Scala","limit":10}"""
  }

  test("Run tool calls and append the tool result") {
    val output =
      """<tool>
        |{
        |  "function_name": "search",
        |  "arguments": "{\"query\":\"Scala\",\"limit\":10}"
        |}
        |</tool>
        |""".stripMargin

    val result = chat.runToolCalls(output).unsafeRunSync()

    result.messages should have size 1
    result.messages.head shouldBe Message(
      Role.Tool,
      "<tool_result>\nSearching for 'Scala' (limit=10)\n</tool_result>"
    )
  }

  test("Run an unknown tool and append the error") {
    val output =
      """<tool>
        |{
        |  "function_name": "unknown",
        |  "arguments": "{}"
        |}
        |</tool>
        |""".stripMargin

    val result = chat.runToolCalls(output).unsafeRunSync()

    result.messages.head shouldBe Message(
      Role.Tool,
      "<tool_result>\nError: Unknown tool: unknown\n</tool_result>"
    )
  }
}
