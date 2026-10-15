package org.scalafcagent.chat

import scala.util.matching.Regex

import cats.effect.IO
import io.circe.ACursor
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Json
import io.circe.parser.decode
import io.circe.parser.parse
import io.circe.syntax._
import org.scalafcagent.tool.Tool
import org.scalafcagent.tool.ToolCall
import org.scalafcagent.tool.ToolEngine
import org.scalafcagent.tool.ToolResult

// --------------------
// Role
// --------------------

// 会話のRole. OpenAI互換APIのroleに写像するのはLLMServer側の責務
// ロールごとのタグ付け (chat template) はOpenAI互換サーバがやってくれるのでこちらでは行わない
sealed trait Role

object Role {

  case object System extends Role

  case object User extends Role

  case object Assistant extends Role

  case object Thinking extends Role

  case object Tool extends Role
}


// --------------------
// Message
// --------------------

case class Message(
  role: Role,
  content: String
)


// --------------------
// Chat Format
// --------------------

// OpenAI互換サーバが面倒を見てくれないもの, つまりTool呼び出しの形式だけをここに置く
// ロールのタグ付けはサーバのチャットテンプレートに任せる
// モデル個別の設定はここには置かず, 必要になったときにChatFormatを組み立てる
case class ChatFormat(
  // LLMの出力からTool呼び出しの一部を取り出す正規表現 (group(1)をdecodeToolCallに渡す)
  toolCallPattern: Regex = ChatFormat.defaultToolCallPattern,
  // 取り出した文字列をToolCallに変換する
  decodeToolCall: String => Option[ToolCall] = ChatFormat.defaultDecodeToolCall,
  // 登録されたToolをsystem promptに載せる文言に変換する
  renderTools: Vector[Tool] => String = ChatFormat.defaultRenderTools,
  // Toolの実行結果を会話に載せるときの形式. これもサーバは関与しないのでこちらで決める
  renderToolResult: String => String = ChatFormat.defaultRenderToolResult
)


object ChatFormat {

  // Prompt設計の <tool>\n{ ... }\n</tool>
  private[chat] val defaultToolCallPattern: Regex =
    """(?s)<tool>\s*(\{.*?\})\s*</tool>""".r

  // Prompt設計の "function_name" をcase classのfunctionNameに割り当てる
  private[chat] val defaultDecodeToolCall: String => Option[ToolCall] =
    input => decode[ToolCall](input)(toolCallDecoder).toOption

  private[chat] implicit val toolCallDecoder: Decoder[ToolCall] =
    Decoder.instance { cursor =>
      for {
        functionName <- cursor.get[String]("function_name")
        // argumentsはJSON文字列でもJSONオブジェクトでも受け付ける
        arguments <- decodeArguments(cursor.downField("arguments"))
      } yield ToolCall(
        functionName = functionName,
        arguments = arguments
      )
    }

  private def decodeArguments(cursor: ACursor): Decoder.Result[String] =
    cursor.as[String].toOption
      .orElse(cursor.focus.map(_.noSpaces))
      .toRight(DecodingFailure("arguments", cursor.history))

  // デフォルト (XMLの <tool> / <tool_result>)
  val Default: ChatFormat = ChatFormat()

  // --------------------
  // Tool instruction
  // --------------------

  // 登録されたToolをsystem prompt用の文言にする
  def defaultRenderTools(tools: Vector[Tool]): String =
    if (tools.isEmpty) {
      ""
    } else {
      val header =
        """## Tool Use
          |
          |You can use the following tools. To call a tool, output:
          |
          |<tool>
          |{"function_name": "<tool name>", "arguments": "<arguments as a JSON string>"}
          |</tool>
          |
          |## Available Tools
          |""".stripMargin

      header + "\n" + tools.map(renderTool).mkString("\n\n")
    }

  private def renderTool(tool: Tool): String = {
    val parameters =
      parse(tool.argumentJSONSchema)
        .getOrElse(Json.fromString(tool.argumentJSONSchema))

    val json =
      Json
        .obj(
          "name" -> tool.name.asJson,
          "description" -> tool.description.asJson,
          "parameters" -> parameters
        )
        .spaces2

    s"<tool>\n$json\n</tool>"
  }

  // --------------------
  // Tool result
  // --------------------

  // Prompt設計の <tool_result>\n...\n</tool_result>
  def defaultRenderToolResult(value: String): String =
    s"<tool_result>\n$value\n</tool_result>"
}


// --------------------
// Chat
// --------------------

// 実行中の会話を管理する
// ScalaFCAgentClientから文章を受け取り, Roleに割り振る
// ScalaFCAgentClientの入力を構築する (LLMServerにはRole付きのmessagesとして渡す)
class Chat(
  val toolEngine: ToolEngine = new ToolEngine,
  val messages: Vector[Message] = Vector.empty,
  val format: ChatFormat = ChatFormat.Default
) {

  // --------------------
  // Append
  // --------------------

  def append(message: Message): Chat =
    new Chat(
      toolEngine = toolEngine,
      messages = messages :+ message,
      format = format
    )

  def system(content: String): Chat =
    append(Message(Role.System, content))

  def user(content: String): Chat =
    append(Message(Role.User, content))

  def assistant(content: String): Chat =
    append(Message(Role.Assistant, content))

  def thinking(content: String): Chat =
    append(Message(Role.Thinking, content))

  def tool(content: String): Chat =
    append(Message(Role.Tool, content))

  // --------------------
  // Tool
  // --------------------

  // LLMの出力からTool呼び出しを取り出す
  def toolCalls(output: String): Vector[ToolCall] =
    format.toolCallPattern
      .findAllMatchIn(output)
      .toVector
      .flatMap { matched =>
        format.decodeToolCall(matched.group(1))
      }

  // LLMの出力に含まれるTool呼び出しを順番に実行する
  def runToolCalls(output: String): IO[Chat] =
    toolCalls(output).foldLeft(IO.pure(this)) {
      (chat, toolCall) =>
        chat.flatMap(_.callTool(toolCall))
    }

  def callTool(toolCall: ToolCall): IO[Chat] =
    toolEngine.call(toolCall).map(renderToolResult).map(tool)

  private def renderToolResult(result: ToolResult): String =
    format.renderToolResult(
      result match {

        case ToolResult.Success(value) =>
          value

        case ToolResult.Error(message) =>
          s"Error: $message"
      }
    )
}
