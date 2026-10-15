package org.scalafcagent.agent

import cats.effect.IO
import cats.effect.Resource
import fs2.Stream
import org.scalafcagent.chat.Chat
import org.scalafcagent.chat.ChatFormat
import org.scalafcagent.chat.Message
import org.scalafcagent.client.Client
import org.scalafcagent.llm.LLMServer
import org.scalafcagent.llm.LLMServerConfig
import org.scalafcagent.llm.OpenAILLMServer
import org.scalafcagent.tool.Tool
import org.scalafcagent.tool.ToolEngine

// --------------------
// Config
// --------------------

// Agentの振る舞いの設定
// トランスポートの設定はLLMServerConfigに分けている
case class AgentConfig(
  format: ChatFormat = ChatFormat.Default,
  systemPrompt: Option[String] = None,
  maxToolCalls: Int = 8
)


// --------------------
// Agent
// --------------------

// UserとLLMServerの間を仲介するコンポーネント (公開用API)
class Agent(
  val client: Client
) {

  // Userからの1ターン. Tool解決まで含めた最終出力を返す
  def send(message: String): IO[String] =
    client.send(message)

  // Userからの1ターン. Tool解決まで含めたモデル出力の差分を到着順に流す
  def sendStream(message: String): Stream[IO, String] =
    client.sendStream(message)

  // 会話 (messages全体) を渡して1ターン実行する (OpenAI互換APIのように履歴を毎回受け取る用途向け)
  def send(messages: Vector[Message]): IO[String] =
    client.send(messages)

  def sendStream(messages: Vector[Message]): Stream[IO, String] =
    client.sendStream(messages)

  // 実行中の会話
  def history: IO[Vector[Message]] =
    client.history

  // 会話を初期状態に戻す
  def reset: IO[Unit] =
    client.reset
}


object Agent {

  // 設定とToolからAgentを作る
  def create(
    llm: LLMServerConfig,
    tools: Tool*
  ): Resource[IO, Agent] =
    create(llm, AgentConfig(), tools: _*)

  def create(
    llm: LLMServerConfig,
    config: AgentConfig,
    tools: Tool*
  ): Resource[IO, Agent] =
    OpenAILLMServer
      .resource(llm)
      .flatMap(server => from(server, config, tools: _*))

  // LLMServerを差し替えて作る (テストや独自トランスポート用)
  def from(
    llmServer: LLMServer,
    tools: Tool*
  ): Resource[IO, Agent] =
    from(llmServer, AgentConfig(), tools: _*)

  def from(
    llmServer: LLMServer,
    config: AgentConfig,
    tools: Tool*
  ): Resource[IO, Agent] =
    Resource.eval(
      Client
        .create(
          llmServer = llmServer,
          chat = initialChat(config, tools: _*),
          maxToolCalls = config.maxToolCalls
        )
        .map(client => new Agent(client))
    )

  private def initialChat(config: AgentConfig, tools: Tool*): Chat = {
    val chat = new Chat(
      toolEngine = new ToolEngine(tools: _*),
      format = config.format
    )

    // Toolsが登録されていれば, ChatFormatに合わせた使い方をsystem promptに載せる
    val sections =
      (config.systemPrompt.toList :+ config.format.renderTools(tools.toVector))
        .filter(_.nonEmpty)

    sections match {
      case Nil =>
        chat

      case _ =>
        chat.system(sections.mkString("\n\n"))
    }
  }
}
