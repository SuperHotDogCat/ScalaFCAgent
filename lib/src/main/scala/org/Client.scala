package org.scalafcagent.client

import cats.effect.IO
import cats.effect.Ref
import fs2.Stream
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

  // Userからのリクエストを処理し, モデル出力の差分を到着順に流す
  def sendStream(userMessage: String): Stream[IO, String] =
    Stream.eval(chat.update(_.user(userMessage))) >> converseStream(maxToolCalls)

  // 会話 (messages全体) を渡して1ターン実行する. 会話は初期状態に置き換える
  // (OpenAI互換APIのように履歴を毎回受け取る用途向け)
  def send(messages: Vector[Message]): IO[String] =
    for {
      _ <- chat.set(initialChat.appendAll(messages))
      output <- converse(maxToolCalls)
    } yield output

  def sendStream(messages: Vector[Message]): Stream[IO, String] =
    Stream.eval(chat.set(initialChat.appendAll(messages))) >> converseStream(maxToolCalls)

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

  // LLMServerの差分を流し, Tool呼び出しがあれば実行して次のターンの差分へ続ける
  private def converseStream(remaining: Int): Stream[IO, String] =
    if (remaining <= 0) {
      Stream.raiseError[IO](
        new RuntimeException(s"Tool call limit exceeded: $maxToolCalls")
      )
    } else {
      Stream.eval(chat.get).flatMap { current =>
        Stream.eval(Ref.of[IO, Vector[String]](Vector.empty)).flatMap { collected =>
          val deltas =
            llmServer
              .stream(current.messages)
              .evalTap(chunk => collected.update(_ :+ chunk))

          // 差分を流し終えてから, 溜めた出力でTool呼び出しを判定する
          deltas ++ Stream.eval(collected.get).flatMap { chunks =>
            val output = chunks.mkString
            val assistant = current.assistant(output)
            val toolCalls = assistant.toolCalls(output)

            Stream
              .eval {
                for {
                  updated <- assistant.runToolCalls(output)
                  _ <- chat.set(updated)
                } yield toolCalls.isEmpty
              }
              .flatMap { finished =>
                if (finished) Stream.empty
                else converseStream(remaining - 1)
              }
          }
        }
      }
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
