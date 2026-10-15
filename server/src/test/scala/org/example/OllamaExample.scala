package org.scalafcagent.server.example

import cats.effect.IO
import cats.effect.IOApp
import org.scalafcagent.agent.AgentConfig
import org.scalafcagent.llm.LLMServerConfig
import org.scalafcagent.server.CurrentTimeTool
import org.scalafcagent.server.HttpServer

// --------------------
// 実行例
// --------------------

// Ollama などのOpenAI互換サーバにつないでHTTPラッパーを立てる例
// 本番の入口ではなく, 使い方と動作確認のための例
//
// 起動:
//   OLLAMA_MODEL=gemma4:e2b ./gradlew :server:runOllamaExample
//
// 別ターミナルから叩く:
//   curl -s -X POST localhost:8080/v1/chat -H 'Content-Type: application/json' \
//     -d '{"session":"u1","message":"今の東京の時刻を教えて"}'
//
// 環境変数:
//   HOST          (default 127.0.0.1)
//   PORT          (default 8080)
//   OLLAMA_HOST   (default http://localhost:11434)
//   OLLAMA_MODEL  (default qwen3:1.7b)
object OllamaExample extends IOApp.Simple {

  private def env(name: String, default: String): String =
    sys.env.getOrElse(name, default)

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).getOrElse(default)

  private val config: HttpServer.Config =
    HttpServer.Config(
      host = env("HOST", "127.0.0.1"),
      port = envInt("PORT", 8080)
    )

  private val llm: LLMServerConfig =
    LLMServerConfig(
      baseUrl = env("OLLAMA_HOST", "http://localhost:11434"),
      model = env("OLLAMA_MODEL", "qwen3:1.7b")
    )

  def run: IO[Unit] =
    HttpServer
      .resource(llm, AgentConfig(), config, new CurrentTimeTool)
      .use { server =>
        IO.println(
          s"listening on http://${server.host}:${server.port} (model=${llm.model})"
        ) *>
          IO.never
      }
}
