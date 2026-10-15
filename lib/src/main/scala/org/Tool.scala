package org.scalafcagent.tool

import cats.effect.IO
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.circe.syntax._

// --------------------
// Tool Call
// --------------------

case class ToolCall(
  functionName: String,
  arguments: String
)


// --------------------
// Tool Result
// --------------------

sealed trait ToolResult

object ToolResult {

  case class Success(value: String) extends ToolResult

  case class Error(message: String) extends ToolResult
}


// --------------------
// Tool Error
// --------------------

case class ToolError(
  message: String
)


// --------------------
// Schema
// --------------------

trait Schema[A] {

  implicit val decoder: Decoder[A]
  implicit val encoder: Encoder[A]

  def encode(input: A): String =
    input.asJson.noSpaces

  def decode(input: String): Either[ToolError, A] =
    io.circe.parser.decode[A](input)
      .left
      .map(error => ToolError(error.getMessage))
}


// --------------------
// Tool
// --------------------

// Engineから扱うための型消去されたTool
trait Tool {

  def name: String

  def description: String

  def argumentJSONSchema: String

  def execute(input: String): IO[ToolResult]
}


// 型付きTool
trait TypedTool[A] extends Tool {

  def schema: Schema[A]

  def run(args: A): IO[ToolResult]

  final override def execute(input: String): IO[ToolResult] =
    schema.decode(input) match {

      case Right(args) =>
        run(args)

      case Left(error) =>
        IO.pure(
          ToolResult.Error(error.message)
        )
    }
}


// --------------------
// Tool Engine
// --------------------

class ToolEngine(
  val tools: Tool*
) {

  private val functionNameToTool: Map[String, Tool] =
    tools.map(tool => tool.name -> tool).toMap

  def call(
    toolCall: ToolCall
  ): IO[ToolResult] =
    functionNameToTool.get(toolCall.functionName) match {

      case Some(tool) =>
        tool.execute(toolCall.arguments)

      case None =>
        IO.pure(
          ToolResult.Error(
            s"Unknown tool: ${toolCall.functionName}"
          )
        )
    }
}
