# Reference: HTTPサーバ (server)

`server`モジュールは, `Agent`をHTTPリクエストで使えるようにするラッパー。
`session`ごとに1つの`Agent` (=1会話) を保持する。

コアの`lib`に重い依存(Pekko HTTP)を持ち込まないよう, 別モジュールにしている。
なお, HTTP実装は[Apache Pekko HTTP](https://pekko.apache.org/)(Akka HTTPのApache-2.0ドロップインFork)を使っている。

## エンドポイント

| Method | Path | リクエスト | レスポンス |
| --- | --- | --- | --- |
| `GET` | `/v1/health` | — | `200` `{"status":"ok"}` |
| `POST` | `/v1/chat` | `{"session":"u1","message":"..."}` | `200` `{"reply":"..."}` |
| `POST` | `/v1/chat/stream` | `{"session":"u1","message":"..."}` | `200` `text/event-stream` (SSE) |
| `POST` | `/v1/sessions/{id}/reset` | — (body不要) | `200` `{"status":"ok"}` |

エラー時は`{"error":"..."}`を返す。

- `400 Bad Request` — リクエストのJSONが不正
- `500 Internal Server Error` — LLMServerやToolの実行に失敗 (`/v1/chat`のみ。`/v1/chat/stream`は接続後にエラーを`data: {"error":"..."}`として流す)

```bash
curl -s localhost:8080/v1/health
curl -s -X POST localhost:8080/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"session":"u1","message":"今の東京の時刻を教えて"}'
curl -s -X POST localhost:8080/v1/sessions/u1/reset
```

### ストリーミング (`/v1/chat/stream`)

LLMServer (Ollamaなど) のストリーミングをSSEで中継する。本文の差分を到着順に流す。

```bash
curl -s -N -X POST localhost:8080/v1/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"session":"u1","message":"1から5まで数えて"}'
```

```
data: {"delta":"1"}

data: {"delta":"、"}

...

data: [DONE]

```

- `data: {"delta":"..."}` — 本文の差分
- `data: {"error":"..."}` — エラー
- `data: [DONE]` — 終端

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

`OpenAILLMServer`を内部で作って立てる。

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

`LLMServer`を差し替えて立てる(テストや独自トランスポート用)。

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

## セッション管理

### `SessionStore`

`sessionId`ごとに`Agent`を保持する。

```scala
trait SessionStore {
  def getOrCreate(sessionId: String): IO[Agent]
  def remove(sessionId: String): IO[Unit]
  def ids: IO[Vector[String]]
}
```

### `SessionStore.inMemory`

`Ref`でメモリ上に保持する実装。`create`は新しいセッションを作るときに呼ばれる。

```scala
object SessionStore {
  def inMemory(create: IO[Agent]): IO[SessionStore]
}
```

`HttpServer`は`LLMServer`を**全セッションで共有**し(HTTPクライアントを1つに抑える), `Agent`だけをセッションごとに作る。

---

## `Routes`

Pekko HTTPの`Route`。`HttpServer`が内部で使う。

```scala
class Routes(sessions: SessionStore) {
  def route: Route
}
```

`Route`を直接使いたい場合(既存のサーバに組み込みたい場合など)はこれを利用する。

---

## Request / Response

```scala
case class ChatRequest(session: String, message: String)
case class ChatResponse(reply: String)
case class ErrorResponse(error: String)
case class StatusResponse(status: String)

object JsonCodecs {
  implicit val chatRequestDecoder: Decoder[ChatRequest]
  implicit val chatResponseEncoder: Encoder[ChatResponse]
  implicit val errorResponseEncoder: Encoder[ErrorResponse]
  implicit val statusResponseEncoder: Encoder[StatusResponse]
}
```

---

## ライフサイクルと設計

`HttpServer.resource` / `from`は`Resource[IO, HttpServer]`を返し, 解放時に以下を行う。

- HTTPのbindingを`unbind`
- `ActorSystem`を`terminate`
- (内部で作った場合)`OpenAILLMServer`の`HttpClient`を`close`

IOからPekkoの`Future`への変換には`cats.effect.unsafe.implicits.global`を使っている。

ストリーミング (`/v1/chat/stream`) では, コアの`fs2.Stream`をPekkoの`Source.queue`に橋渡しして`Source[ByteString, _]`として返す。Sourceがmaterializeされた時点でfs2のStreamを走らせ, 差分をqueueへ積む。クライアントが切断するとqueueが閉じ, fs2側のStreamも止まる (LLMServerへの接続も閉じる)。

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
- 会話の永続化は未実装(`SessionStore.inMemory`のみ)
- セッションのTTL・自動削除は未実装(`/v1/sessions/{id}/reset`で明示的に消す)
- `SessionStore.getOrCreate`は完全には原子的ではなく, 同一`sessionId`への同時初回アクセスで稀に二重生成しうる(先に入った方を採用する)
