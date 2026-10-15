package org.scalafcagent.chat

import scala.util.matching.Regex

import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import org.junit.runner.RunWith
import org.scalafcagent.tool.SearchTool
import org.scalafcagent.tool.ToolCall
import org.scalafcagent.tool.ToolEngine
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

// --------------------
// モデルごとのChatFormatの例
// --------------------

// 本番コードには入れず, 必要になったときにこの形で組み立てる
// 例はすべて search ツール (SearchTool) を呼ぶ
@RunWith(classOf[JUnitRunner])
class ChatFormatExamplesSpec extends AnyFunSuite with Matchers {

  private def newChat(format: ChatFormat): Chat =
    new Chat(
      toolEngine = new ToolEngine(new SearchTool),
      format = format
    )

  private val expectedResult: String =
    "Searching for 'Scala' (limit=10)"

  // --------------------
  // Common
  // --------------------

  private def quote(value: String): String =
    "\"" + value + "\""

  // JSONの値らしきものはそのまま, それ以外は文字列にする
  private def jsonValue(raw: String): String = {
    val value = raw.trim

    if (
      value.matches("""-?\d+""") ||
      value.matches("""-?\d*\.\d+""") ||
      value == "true" ||
      value == "false" ||
      value == "null"
    ) {
      value
    } else {
      quote(value)
    }
  }

  // --------------------
  // Qwen2.5 / Qwen3 / QwQ / Nous Hermes (Hermes形式)
  // --------------------

  // <tool_call>\n{"name": "search", "arguments": { ... }}\n</tool_call>
  // <tool_response>\n...\n</tool_response>
  // argumentsはJSONオブジェクトでも文字列でも受け付ける
  private val hermesFormat: ChatFormat =
    ChatFormat(
      toolCallPattern = """(?s)<tool_call>\s*(\{.*?\})\s*</tool_call>""".r,
      decodeToolCall = decodeHermesCall,
      renderToolResult = value => s"<tool_response>\n$value\n</tool_response>"
    )

  private def decodeHermesCall(input: String): Option[ToolCall] =
    parse(input).toOption.flatMap { json =>
      val cursor = json.hcursor
      val arguments = cursor.downField("arguments")

      for {
        name <- cursor.get[String]("name").toOption
        args <- arguments.as[String].toOption
          .orElse(arguments.focus.map(_.noSpaces))
      } yield ToolCall(
        functionName = name,
        arguments = args
      )
    }

  test("Hermes (Qwen2.5 / Qwen3 / QwQ / Nous Hermes)") {
    val output =
      """<tool_call>
        |{"name": "search", "arguments": {"query": "Scala", "limit": 10}}
        |</tool_call>
        |""".stripMargin

    val result = newChat(hermesFormat).runToolCalls(output).unsafeRunSync()

    result.messages.head shouldBe Message(
      Role.Tool,
      s"<tool_response>\n$expectedResult\n</tool_response>"
    )
  }

  // --------------------
  // Qwen3-Coder (XML形式)
  // --------------------

  // <tool_call>
  // <function=search>
  // <parameter=query>Scala</parameter>
  // <parameter=limit>10</parameter>
  // </function>
  // </tool_call>
  private val qwen3CoderFormat: ChatFormat =
    ChatFormat(
      toolCallPattern =
        """(?s)<tool_call>\s*(<function=.*?</function>)\s*</tool_call>""".r,
      decodeToolCall = decodeQwen3CoderCall,
      renderToolResult = value => s"<tool_response>\n$value\n</tool_response>"
    )

  private val functionNamePattern: Regex =
    """<function=([^>\s]+)>""".r

  private val parameterPattern: Regex =
    """<parameter=([^>\s]+)>(.*?)</parameter>""".r

  private def decodeQwen3CoderCall(input: String): Option[ToolCall] =
    functionNamePattern.findFirstMatchIn(input).map { name =>
      val arguments = parameterPattern
        .findAllMatchIn(input)
        .map { parameter =>
          s"${quote(parameter.group(1))}:${jsonValue(parameter.group(2))}"
        }
        .mkString("{", ",", "}")

      ToolCall(
        functionName = name.group(1),
        arguments = arguments
      )
    }

  test("Qwen3-Coder (XML形式)") {
    val output =
      """<tool_call>
        |<function=search>
        |<parameter=query>Scala</parameter>
        |<parameter=limit>10</parameter>
        |</function>
        |</tool_call>
        |""".stripMargin

    val result = newChat(qwen3CoderFormat).runToolCalls(output).unsafeRunSync()

    result.messages.head shouldBe Message(
      Role.Tool,
      s"<tool_response>\n$expectedResult\n</tool_response>"
    )
  }

  // --------------------
  // DeepSeek V3.1 / V3.2
  // --------------------

  // 特殊トークンを使う (｜ = U+FF5C, ▁ = U+2581)
  private val bar: String = "\uFF5C"
  private val us: String = "\u2581"

  private def dsToken(name: String): String =
    "<" + bar + name.replace("_", us) + bar + ">"

  private val deepSeekFormat: ChatFormat =
    ChatFormat(
      toolCallPattern =
        (
          "(?s)" +
            Regex.quote(dsToken("tool_call_begin")) +
            "(.*?)" +
            Regex.quote(dsToken("tool_call_end"))
        ).r,
      decodeToolCall = decodeDeepSeekCall,
      renderToolResult = value =>
        dsToken("tool_output_begin") + value + dsToken("tool_output_end")
    )

  private def decodeDeepSeekCall(input: String): Option[ToolCall] = {
    val separator = dsToken("tool_sep")
    val index = input.indexOf(separator)

    if (index < 0) {
      None
    } else {
      Some(
        ToolCall(
          functionName = input.substring(0, index).trim,
          arguments = input.substring(index + separator.length).trim
        )
      )
    }
  }

  test("DeepSeek V3.1 / V3.2") {
    val output =
      dsToken("tool_calls_begin") +
        dsToken("tool_call_begin") +
        "search" +
        dsToken("tool_sep") +
        """{"query":"Scala","limit":10}""" +
        dsToken("tool_call_end") +
        dsToken("tool_calls_end")

    val result = newChat(deepSeekFormat).runToolCalls(output).unsafeRunSync()

    result.messages.head shouldBe Message(
      Role.Tool,
      dsToken("tool_output_begin") + expectedResult + dsToken("tool_output_end")
    )
  }

  // --------------------
  // Gemma 3 (ネイティブのToolトークンはなくPython風のコードブロックを使う)
  // --------------------

  // ```tool_code
  // search(query="Scala", limit=10)
  // ```
  // ```tool_output
  // ...result...
  // ```
  private val gemma3Format: ChatFormat =
    ChatFormat(
      toolCallPattern = """(?s)```tool_code\s*(.*?)\s*```""".r,
      decodeToolCall = decodeGemma3Call,
      renderToolResult = value => s"```tool_output\n$value\n```"
    )

  private val gemmaCallPattern: Regex =
    """(\w+)\s*\((.*)\)""".r

  private val gemmaArgumentPattern: Regex =
    """(\w+)\s*=\s*("[^"]*"|'[^']*'|[^,]+)""".r

  private def decodeGemma3Call(input: String): Option[ToolCall] =
    gemmaCallPattern.findFirstMatchIn(input).map { call =>
      val arguments = gemmaArgumentPattern
        .findAllMatchIn(call.group(2))
        .map { argument =>
          s"${quote(argument.group(1))}:${gemmaValue(argument.group(2))}"
        }
        .mkString("{", ",", "}")

      ToolCall(
        functionName = call.group(1),
        arguments = arguments
      )
    }

  private def gemmaValue(value: String): String = {
    val trimmed = value.trim

    if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
      quote(trimmed.substring(1, trimmed.length - 1))
    } else {
      trimmed
    }
  }

  test("Gemma 3 (Python風のtool_codeブロック)") {
    val output =
      "```tool_code\nsearch(query=\"Scala\", limit=10)\n```"

    val result = newChat(gemma3Format).runToolCalls(output).unsafeRunSync()

    result.messages.head shouldBe Message(
      Role.Tool,
      s"```tool_output\n$expectedResult\n```"
    )
  }
}
