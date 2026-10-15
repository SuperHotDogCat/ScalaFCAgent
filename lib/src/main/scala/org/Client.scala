package org.scalafcagent.client

import cats.effect.IO
import cats.effect.Ref
import org.scalafcagent.chat.Chat
import org.scalafcagent.chat.Message
import org.scalafcagent.llm.LLMServer

// --------------------
// Client
// --------------------

// UserとScalaFCAgentChatからのリクエストを受け取りLLMServerへリクエストを送る
// 出力に応じてUserとScalaFCAgentChatにリクエストを返す
class Client(
  val llmServer: LLMServer,
  val chat: Ref[IO, Chat],
  val initialChat: Chat,
  val maxToolCalls: Int = 8
) {

  // --------------------
  // User
  // --------------------

  // Userからのリクエストを処理し, 最終的なモデル出力を返す
  def send(userMessage: String): IO[String] =
    for {
      _ <- chat.update(_.user(userMessage))
      output <- converse(maxToolCalls)
    } yield output

  // 実行中の会話
  def history: IO[Vector[Message]] =
    chat.get.map(_.messages)

  // 会話を初期状態 (system promptなど) に戻す
  def reset: IO[Unit] =
    chat.set(initialChat)

  // --------------------
  // Chat / LLMServer
  // --------------------

  // LLMServerへ送り, Tool呼び出しがあればChatに渡して繰り返す
  private def converse(remaining: Int): IO[String] =
    if (remaining <= 0) {
      IO.raiseError(
        new RuntimeException(s"Tool call limit exceeded: $maxToolCalls")
      )
    } else {
      for {
        current <- chat.get

        // Role付きの会話をLLMServerへ送る (タグ付けはOpenAI互換サーバが行う)
        output <- llmServer.complete(current.messages)

        // 出力をAssistantに割り当て, Tool呼び出しを実行して会話を更新する
        assistant = current.assistant(output)
        toolCalls = assistant.toolCalls(output)
        updated <- assistant.runToolCalls(output)
        _ <- chat.set(updated)

        // Tool呼び出しが無ければUserへ返す
        result <- if (toolCalls.isEmpty) IO.pure(output) else converse(remaining - 1)
      } yield result
    }
}


object Client {

  def create(
    llmServer: LLMServer,
    chat: Chat = new Chat,
    maxToolCalls: Int = 8
  ): IO[Client] =
    Ref.of[IO, Chat](chat).map { ref =>
      new Client(
        llmServer = llmServer,
        chat = ref,
        initialChat = chat,
        maxToolCalls = maxToolCalls
      )
    }
}
