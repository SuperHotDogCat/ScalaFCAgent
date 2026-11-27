# org.scalafcagent.server

`Agent`をOpenAI互換のHTTP APIとして公開する`server`モジュール。HTTP実装は[Apache Pekko HTTP](https://pekko.apache.org/) (Akka HTTPのApache-2.0のfork)。
コアの`lib`に重い依存を持ち込まないよう, 別モジュールにしている。

```scala
import org.scalafcagent.server.HttpServer
```

| 名前 | 種類 | 内容 |
| --- | --- | --- |
| [HTTPエンドポイント](#httpエンドポイント) | — | `POST /v1/chat/completions`, `GET /v1/health` |
| [`HttpServer`](#httpserver) | class | 起動したサーバ |
| [`HttpServer.Config`](#httpserverconfig) | case class | バインド先 |
| [`HttpServer.resource`](#httpserverresource) | method | `LLMServerConfig`から立てる |
| [`HttpServer.from`](#httpserverfrom) | method | 任意の`LLMServer`から立てる |
| [`Routes`](#routes) | class | Pekko HTTPの`Route` |
| [`ChatCompletionRequest`](#chatcompletionrequest) | case class | リクエストのモデル |

---

## HTTPエンドポイント

サーバは会話を保持しない (ステートレス)。クライアントは毎回, 会話全体を`messages`で送る。

| Method | Path | レスポンス |
| --- | --- | --- |
| `POST` | `/v1/chat/completions` | `200` `chat.completion` (JSON) |
| `POST` | `/v1/chat/completions` (`"stream": true`) | `200` `text/event-stream` (`chat.completion.chunk`のSSE) |
| `GET` | `/v1/health` | `200` `{"status":"ok"}` |

### `POST /v1/chat/completions`

**Request body:**

- **model** (`string`, 任意) — レスポンスの`model`にそのまま返す。省略時はサーバのモデル名。実際に使うモデルはサーバの設定で決まる
- **messages** (`array`, 必須) — `{"role": ..., "content": ...}`の配列。`role`は`system` / `developer` / `user` / `assistant` / `tool`。`content`は文字列のみ
- **stream** (`boolean`, 任意, default: `false`) — `true`でSSE

ほかのフィールド (`tools`, `tool_choice`, `temperature`など) は無視される。

サーバは`messages`の前に, Toolの使い方 (と`AgentConfig.systemPrompt`) のsystemメッセージを差し込んでから[`Agent.send(messages)`](./agent.md#send-messages)を実行する。

**Response (非ストリーミング):**

```json
{
  "id": "chatcmpl-0f8e...",
  "object": "chat.completion",
  "created": 1792089308,
  "model": "gemma4:e2b",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "現在の東京の時刻は ... です。"},
      "finish_reason": "stop"
    }
  ]
}
```

`usage`は返さない。`finish_reason`は常に`stop`。

**Response (ストリーミング):**

最初にroleだけの差分, 次に本文の差分, 最後に`finish_reason: "stop"`の差分と`data: [DONE]`を送る。

```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"gemma4:e2b","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk",...,"choices":[{"index":0,"delta":{"content":"現在"},"finish_reason":null}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk",...,"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

Toolを呼ぶターンでは, モデルが出した`<tool>...</tool>`も本文の差分として流れる。

**Errors:**

```json
{"error": {"message": "...", "type": "invalid_request_error"}}
```

| ステータス | `type` | 原因 |
| --- | --- | --- |
| `400` | `invalid_request_error` | JSONが不正, `messages[].content`が文字列でない, 未対応の`role` (`Unsupported role: <role>`) |
| `500` | `server_error` | LLMServerのエラー, Toolの`IO`の失敗, Tool呼び出し回数の超過 |

ストリーミングでは, `200`を返したあとに失敗するとエラーをステータスでは返せない。
その場合はerrorオブジェクトを独立したSSEフレームとして送り, finishの差分 (`finish_reason: "stop"`) は送らずに`data: [DONE]`で終える。

```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk",...,"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"error":{"message":"LLMServer error 500: ...","type":"server_error"}}

data: [DONE]
```

クライアントは, `choices`の代わりに`error`を持つフレームが来たかで失敗を検出できる。

### `GET /v1/health`

**Response:**

```json
{"status": "ok"}
```

---

## `HttpServer`

```scala
class HttpServer(val binding: Http.ServerBinding) {
  def host: String
  def port: Int
}
```

起動したHTTPサーバ。[`HttpServer.resource`](#httpserverresource) / [`HttpServer.from`](#httpserverfrom)の`Resource`から受け取る。

**Parameters:**

- **binding** (`org.apache.pekko.http.scaladsl.Http.ServerBinding`) — Pekko HTTPのbinding

**Methods:**

- **host** (`String`) — バインドしたホスト
- **port** (`Int`) — バインドしたポート。`Config(port = 0)`で起動した場合は実際に割り当てられたポート

---

## `HttpServer.Config`

```scala
case class Config(
  host: String = "127.0.0.1",
  port: Int = 8080
)
```

**Parameters:**

- **host** (`String`, default: `"127.0.0.1"`) — バインドするホスト。外部から受けるなら`"0.0.0.0"`
- **port** (`Int`, default: `8080`) — バインドするポート。`0`で空いているポート

---

## `HttpServer.resource`

```scala
object HttpServer {
  def resource(llm: LLMServerConfig, tools: Tool*): Resource[IO, HttpServer]

  def resource(
    llm: LLMServerConfig,
    agentConfig: AgentConfig,
    config: Config,
    tools: Tool*
  ): Resource[IO, HttpServer]
}
```

`LLMServerConfig`から[`OpenAILLMServer`](./llm.md#openaillmserver)を作ってサーバを立てる。レスポンスの`model`の既定値は`llm.model`。

**Parameters:**

- **llm** ([`LLMServerConfig`](./llm.md#llmserverconfig)) — 接続先のLLM
- **agentConfig** ([`AgentConfig`](./agent.md#agentconfig), default: `AgentConfig()`) — リクエストごとに作るAgentの設定
- **config** ([`Config`](#httpserverconfig), default: `Config()`) — バインド先
- **tools** (`Tool*`) — 登録するTool。全リクエストで有効

**Returns:**

- `Resource[IO, HttpServer]` — 確保時にbindする。解放時にunbindし, `ActorSystem`を止め, `HttpClient`を閉じる

**Example:**

```scala
import cats.effect.{IO, IOApp}
import org.scalafcagent.agent.AgentConfig
import org.scalafcagent.llm.LLMServerConfig
import org.scalafcagent.server.HttpServer

object ServerMain extends IOApp.Simple {
  private val llm = LLMServerConfig(baseUrl = "http://localhost:11434", model = "gemma4:e2b")

  def run: IO[Unit] =
    HttpServer
      .resource(llm, AgentConfig(), HttpServer.Config(port = 8080), new CurrentTimeTool)
      .use { server =>
        IO.println(s"listening on http://${server.host}:${server.port}") *> IO.never
      }
}
```

実行方法とクライアントの例は[OpenAI互換HTTPサーバ](../examples/serving/openai_compatible_server.md)を参照。

---

## `HttpServer.from`

```scala
object HttpServer {
  def from(llmServer: LLMServer, tools: Tool*): Resource[IO, HttpServer]

  def from(
    llmServer: LLMServer,
    agentConfig: AgentConfig,
    config: Config,
    tools: Tool*
  ): Resource[IO, HttpServer]
}
```

任意の[`LLMServer`](./llm.md#llmserver)でサーバを立てる。テストや独自トランスポート用。
サーバ側のモデル名を持たないので, リクエストで`model`を省略するとレスポンスの`model`は空文字列になる。

**Parameters:**

- **llmServer** ([`LLMServer`](./llm.md#llmserver)) — 全リクエストで共有する。解放はしない
- **agentConfig** ([`AgentConfig`](./agent.md#agentconfig), default: `AgentConfig()`)
- **config** ([`Config`](#httpserverconfig), default: `Config()`)
- **tools** (`Tool*`)

**Returns:**

- `Resource[IO, HttpServer]`

**Example:**

```scala
val mock = LLMServer(_ => IO.pure("Hello!"))

HttpServer.from(mock, AgentConfig(), HttpServer.Config(port = 0)).use { server =>
  // http://127.0.0.1:${server.port}/v1/chat/completions にリクエストを送って検証する
  IO.unit
}
```

---

## `Routes`

```scala
class Routes(
  llmServer: LLMServer,
  agentConfig: AgentConfig,
  model: String,
  tools: Vector[Tool]
) {
  def route: Route
}
```

[HTTPエンドポイント](#httpエンドポイント)を実装するPekko HTTPの`Route`。`HttpServer`は内部でこれを使う。
既存のPekko HTTPサーバに組み込みたい場合に直接使う。

**Parameters:**

- **llmServer** ([`LLMServer`](./llm.md#llmserver)) — 全リクエストで共有する
- **agentConfig** ([`AgentConfig`](./agent.md#agentconfig)) — リクエストごとに作るAgentの設定
- **model** (`String`) — リクエストで`model`が省略されたときにレスポンスへ入れる名前
- **tools** (`Vector[Tool]`) — 登録するTool

**Returns (`route`):**

- `org.apache.pekko.http.scaladsl.server.Route` — `/v1`以下のルート。LLMの往復は長くなるため, Pekkoのリクエストタイムアウトは無効にしている

**Example:**

```scala
import org.apache.pekko.http.scaladsl.server.Directives._

val agentRoutes = new Routes(llmServer, AgentConfig(), "gemma4:e2b", Vector(new CurrentTimeTool))

val route = concat(
  agentRoutes.route,
  path("hello")(get(complete("hello")))
)
```

---

## `ChatCompletionRequest`

```scala
case class RequestMessage(role: String, content: String)

case class ChatCompletionRequest(
  model: Option[String] = None,
  messages: Vector[RequestMessage] = Vector.empty,
  stream: Option[Boolean] = None
)

case class StatusResponse(status: String)

object JsonCodecs {
  implicit val requestMessageDecoder: Decoder[RequestMessage]
  implicit val chatCompletionRequestDecoder: Decoder[ChatCompletionRequest]
  implicit val statusResponseEncoder: Encoder[StatusResponse]
}
```

`POST /v1/chat/completions`のリクエストのうち, 読むフィールドだけを持つモデル。`chat.completion` / `chat.completion.chunk` / `error`のレスポンスは`io.circe.Json`で直接組み立てている。

**`role`の変換:**

| リクエストの`role` | `Role` |
| --- | --- |
| `system`, `developer` | `Role.System` |
| `user` | `Role.User` |
| `assistant` | `Role.Assistant` |
| `tool` | `Role.Tool` |
| それ以外 | `400 Unsupported role` |

---

## ライフサイクルと実装

- `LLMServer`は全リクエストで共有し, `Agent`はリクエストごとに作る。同時リクエストの会話は混ざらない
- IOからPekkoの`Future`への変換には`cats.effect.unsafe.implicits.global`を使う
- ストリーミングでは, コアの`fs2.Stream`をPekkoの`Source.queue`に橋渡しする。クライアントが切断するとqueueが閉じ, fs2側のStreamも止まる (LLMServerへの接続も閉じる)

## 制約

- 認証・レート制限は未実装
- `messages[].content`は文字列のみ (マルチモーダルの配列や`null`は`400`)
- OpenAIの`tools` / `tool_choice` / `response_format`は未対応。Toolはサーバに登録したものが常に有効
