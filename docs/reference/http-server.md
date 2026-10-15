# Reference: HTTPサーバ (server)

`server`モジュールは, `Agent`をHTTPリクエストで使えるようにするラッパー。
**OpenAI互換**の `POST /v1/chat/completions` を提供する。

状態を持たない**完全ステートレス**で, 会話は毎回リクエストの`messages`として受け取る。
(サーバー側の会話保持はしない。履歴はクライアントが送る)

コアの`lib`に重い依存(Pekko HTTP)を持ち込まないよう, 別モジュールにしている。
なお, HTTP実装は[Apache Pekko HTTP](https://pekko.apache.org/)(Akka HTTPのApache-2.0ドロップインFork)を使っている。

## エンドポイント

| Method | Path | リクエスト | レスポンス |
| --- | --- | --- | --- |
| `POST` | `/v1/chat/completions` | [Chat Completion リクエスト](#リクエスト) | `200` `chat.completion` |
| `POST` | `/v1/chat/completions` (`stream: true`) | 同上 | `200` `text/event-stream` (SSE) |
| `GET` | `/v1/health` | — | `200` `{"status":"ok"}` |

エラーはOpenAI互換の形で返す。

```json
{"error":{"message":"...","type":"invalid_request_error"}}
```

- `400 Bad Request` — リクエストのJSONが不正, または未対応の`role`
- `500 Internal Server Error` — LLMServerやToolの実行に失敗
  - ストリーミングでは接続後に `data: {"error":{"message":"...","type":"server_error"}}` として流す

### リクエスト

OpenAIの Chat Completion リクエストのうち, 使うフィールドだけを読む。

```json
{
  "model": "gemma4:e2b",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "今の東京の時刻を教えて"}
  ],
  "stream": false
}
```

- `model` (任意) — レスポンスの`model`にそのまま返す。未指定ならサーバーのモデル名
- `messages` (必須) — `role`は `system` / `developer` / `user` / `assistant` / `tool`
- `stream` (任意, 既定`false`) — `true`でSSE

`messages`はそのまま会話として使う。Toolの使い方のsystem promptはサーバーが先頭に差し込む。

### レスポンス (非ストリーミング)

```json
{
  "id": "chatcmpl-...",
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

### ストリーミング (`stream: true`)

LLMServer (Ollamaなど) のストリーミングをSSEで中継する。OpenAIと同じ `chat.completion.chunk` を並べ, 最後に `data: [DONE]` を返す。

```bash
curl -s -N -X POST localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma4:e2b","messages":[{"role":"user","content":"1から5まで数えて"}],"stream":true}'
```

```
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","created":...,"model":"gemma4:e2b","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk",...,"choices":[{"index":0,"delta":{"content":"1"},"finish_reason":null}]}

...

data: {"id":"chatcmpl-...","object":"chat.completion.chunk",...,"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

```

Toolを呼ぶターンでは, モデルが出力したTool呼び出し (`<tool>...</tool>`) もそのまま差分として流れてから, Toolの実行と次のターンの差分へ続く。

---

## `HttpServer`

```scala
class HttpServer(val binding: Http.ServerBinding) {
  def host: String // バインドしたホスト
  def port: Int    // バインドしたポート
}
```

### `HttpServer.Config`

```scala
case class Config(
  host: String = "127.0.0.1",
  port: Int = 8080
)
```

### `HttpServer.resource`

`OpenAILLMServer`を内部で作って立てる。レスポンスの`model`名には`LLMServerConfig.model`を使う。

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

### `HttpServer.from`

`LLMServer`を差し替えて立てる(テストや独自トランスポート用)。`model`名はリクエストの`model`を使う。

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

---

## `Routes`

Pekko HTTPの`Route`。`HttpServer`が内部で使う。

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

`Route`を直接使いたい場合(既存のサーバに組み込みたい場合など)はこれを利用する。

---

## Request / Response

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

`chat.completion` / `chat.completion.chunk` / `error` は`io.circe.Json`で組み立てる。

---

## ライフサイクルと設計

`HttpServer.resource` / `from`は`Resource[IO, HttpServer]`を返し, 解放時に以下を行う。

- HTTPのbindingを`unbind`
- `ActorSystem`を`terminate`
- (内部で作った場合)`OpenAILLMServer`の`HttpClient`を`close`

IOからPekkoの`Future`への変換には`cats.effect.unsafe.implicits.global`を使っている。

`LLMServer`は全リクエストで共有し, リクエストごとに`Agent`を作る(ステートレス)。

ストリーミングでは, コアの`fs2.Stream`をPekkoの`Source.queue`に橋渡しして`Source[ByteString, _]`として返す。Sourceがmaterializeされた時点でfs2のStreamを走らせ, 差分をqueueへ積む。クライアントが切断するとqueueが閉じ, fs2側のStreamも止まる (LLMServerへの接続も閉じる)。

---

## 実行例

リポジトリにOllamaにつなぐ実行例がある(`server/src/test/scala/org/example/OllamaExample.scala`)。

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

---

## 制約

- 認証・レート制限は未実装
- 会話はサーバー側に保持しない (ステートレス)。履歴はクライアントが毎回`messages`で送る
- `messages[].content`は文字列のみ (マルチモーダルの配列には未対応)
- `tools` / `tool_choice` / `response_format` などOpenAIのツール指定フィールドは未対応。Toolはサーバーが`HttpServer.resource`/`from`に登録したものが常に有効
