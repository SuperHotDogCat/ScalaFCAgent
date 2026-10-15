# Quick Start

最小構成のToolを1つ作り, LLMServerと会話させるところまでをまとめる。
LLMServerはOpenAI互換APIであれば何でもよい(例ではOllamaを使う)。

## 前提

- JDK 21
- Scala 2.13
- OpenAI互換APIを提供するサーバ (例: Ollama, vLLM)
  - Ollamaの場合は `ollama pull gemma4:e2b` などでモデルを用意しておく

## 依存

Gradleの場合は`lib` (HTTPで使うなら`server`も) を追加する。

```groovy
dependencies {
    implementation project(':lib')
    // HTTPサーバとして使う場合
    implementation project(':server')
}
```

## 1. Toolを作る

`TypedTool`を継承すると, LLMからの未検証なJSON文字列と型安全な処理の境界を`Schema`で作れる。

```scala
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.scalafcagent.tool.{Schema, ToolResult, TypedTool}

// Toolの引数
case class WeatherArguments(city: String)

object WeatherSchema extends Schema[WeatherArguments] {
  implicit val decoder: Decoder[WeatherArguments] = deriveDecoder
  implicit val encoder: Encoder[WeatherArguments] = deriveEncoder
}

class WeatherTool extends TypedTool[WeatherArguments] {

  val name: String = "weather"

  val description: String = "指定した都市の天気を返す"

  // 引数のJSON Schema (今のところ手入力)
  val argumentJSONSchema: String =
    """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""

  val schema: Schema[WeatherArguments] = WeatherSchema

  def run(args: WeatherArguments): IO[ToolResult] =
    IO.pure(ToolResult.Success(s"${args.city} は晴れです"))
}
```

## 2. AgentでLLMServerと会話する

`Agent`がUserとLLMServerの間を仲介する。`Agent.create`は`Resource[IO, Agent]`を返す。

```scala
import cats.effect.{IO, IOApp}
import org.scalafcagent.agent.Agent
import org.scalafcagent.llm.LLMServerConfig

object Main extends IOApp.Simple {

  private val llm = LLMServerConfig(
    baseUrl = "http://localhost:11434", // Ollama
    model = "gemma4:e2b"
  )

  def run: IO[Unit] =
    Agent.create(llm, new WeatherTool).use { agent =>
      agent.send("東京の天気を教えて").flatMap(IO.println)
    }
}
```

`Agent.create` の引数:

```scala
Agent.create(llm: LLMServerConfig, tools: Tool*): Resource[IO, Agent]
Agent.create(llm: LLMServerConfig, config: AgentConfig, tools: Tool*): Resource[IO, Agent]
```

Toolを登録すると, その使い方 (`<tool>`の出力形式) が自動で初期のsystem promptに載る。
Toolを登録しなければ, 普通のUserとLLMServerの会話になる。

## 3. HTTPサーバとして立てる

`server`モジュールを使うと, そのままHTTPで受け付けられる。
OpenAI互換の `POST /v1/chat/completions` で, 状態を持たない (履歴は毎回`messages`で送る)。

```scala
import cats.effect.{IO, IOApp}
import org.scalafcagent.llm.LLMServerConfig
import org.scalafcagent.server.HttpServer

object ServerMain extends IOApp.Simple {

  private val llm = LLMServerConfig(
    baseUrl = "http://localhost:11434",
    model = "gemma4:e2b"
  )

  def run: IO[Unit] =
    HttpServer.resource(llm, new WeatherTool).use { server =>
      IO.println(s"listening on http://${server.host}:${server.port}") *> IO.never
    }
}
```

叩く:

```bash
curl -s localhost:8080/v1/health
# {"status":"ok"}

curl -s -X POST localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma4:e2b","messages":[{"role":"user","content":"東京の天気を教えて"}]}'
# {"id":"chatcmpl-...","object":"chat.completion",...,"choices":[{"index":0,"message":{"role":"assistant","content":"東京は晴れです"},"finish_reason":"stop"}]}
```

`stream: true` にするとOpenAIと同じSSEで返る:

```bash
curl -s -N -X POST localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma4:e2b","messages":[{"role":"user","content":"1から5まで数えて"}],"stream":true}'
# data: {"id":"chatcmpl-...","object":"chat.completion.chunk",...,"choices":[{"index":0,"delta":{"content":"1"},"finish_reason":null}]}
# ...
# data: [DONE]
```

## 4. 用意してある実行例で試す

リポジトリにはOllamaにつなぐ実行例がある。Toolは`CurrentTimeTool`。

```bash
OLLAMA_MODEL=gemma4:e2b ./gradlew :server:runOllamaExample
```

環境変数:

| 変数 | デフォルト |
| --- | --- |
| `HOST` | `127.0.0.1` |
| `PORT` | `8080` |
| `OLLAMA_HOST` | `http://localhost:11434` |
| `OLLAMA_MODEL` | `qwen3:1.7b` |

## 次に読む

- [コアのリファレンス](./reference/core.md)
- [HTTPサーバのリファレンス](./reference/http-server.md)
