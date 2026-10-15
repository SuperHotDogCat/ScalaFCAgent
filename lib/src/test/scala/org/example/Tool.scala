
package org.scalafcagent.tool

import org.scalafcagent.tool
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe.parser._
import io.circe.syntax._
import io.circe.generic.auto._
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import io.circe._
import io.circe.generic.semiauto._
import cats.effect.IO
import cats.effect.unsafe.implicits.global

case class SearchArguments(
  query: String,
  limit: Int
)

object SearchSchema extends Schema[SearchArguments] {

  implicit val decoder: Decoder[SearchArguments] =
    deriveDecoder[SearchArguments]

  implicit val encoder: Encoder[SearchArguments] =
    deriveEncoder[SearchArguments]
}


class SearchTool extends TypedTool[SearchArguments] {

  override val name: String =
    "search"

  override val description: String =
    "Search the web"

  override val argumentJSONSchema: String =
    """
      |{
      |  "type": "object",
      |  "properties": {
      |    "query": {
      |      "type": "string"
      |    },
      |    "limit": {
      |      "type": "integer"
      |    }
      |  },
      |  "required": ["query", "limit"]
      |}
      |""".stripMargin

  override val schema: Schema[SearchArguments] =
    SearchSchema

  override def run(
    args: SearchArguments
  ): IO[ToolResult] =
    IO.pure(
      ToolResult.Success(
        s"Searching for '${args.query}' (limit=${args.limit})"
      )
    )
}

@RunWith(classOf[JUnitRunner])
class ToolEngineSpec extends AnyFunSuite with Matchers {

  private val engine = new ToolEngine(
    new SearchTool
  )

  test("Call tool") {
    val toolCall = ToolCall(
      functionName = "search",
      arguments = """{"query":"Scala","limit":10}"""
    )

    val result = engine.call(toolCall).unsafeRunSync()

    result shouldBe ToolResult.Success(
      "Searching for 'Scala' (limit=10)"
    )
  }

  test("Call unknown tool") {
    val toolCall = ToolCall(
      functionName = "unknown",
      arguments = """{}"""
    )

    val result = engine.call(toolCall).unsafeRunSync()

    result shouldBe ToolResult.Error(
      "Unknown tool: unknown"
    )
  }

  test("Call tool with invalid arguments") {
    val toolCall = ToolCall(
      functionName = "search",
      arguments = """{"query":"Scala"}"""
    )

    val result = engine.call(toolCall).unsafeRunSync()

    result match {
      case ToolResult.Error(message) =>
        message should not be empty

      case ToolResult.Success(_) =>
        fail("Expected ToolResult.Error")
    }
  }

  test("Call tool with invalid JSON") {
    val toolCall = ToolCall(
      functionName = "search",
      arguments = """{"query":"Scala","limit":}"""
    )

    val result = engine.call(toolCall).unsafeRunSync()

    result match {
      case ToolResult.Error(message) =>
        message should not be empty

      case ToolResult.Success(_) =>
        fail("Expected ToolResult.Error")
    }
  }
}
