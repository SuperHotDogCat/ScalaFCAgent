# org.scalafcagent.llm

LLMServerとの通信。

```scala
import org.scalafcagent.llm.{LLMServer, LLMServerConfig, OpenAILLMServer}
```

| 名前 | 種類 | 内容 |
| --- | --- | --- |
| [`LLMServer`](#llmserver) | trait | トランスポート |
| [`LLMServer.apply`](#llmserverapply) | method | 関数からLLMServerを作る |
| [`LLMServerConfig`](#llmserverconfig) | case class | 接続先・モデル・サンプリングの設定 |
| [`OpenAILLMServer`](#openaillmserver) | class | OpenAI互換APIの実装 |

---

## `LLMServer`

```scala
trait LLMServer {
  def complete(messages: Vector[Message]): IO[String]
  def stream(messages: Vector[Message]): Stream[IO, String] = Stream.eval(complete(messages))
}
```

外部 (vLLM, Ollamaなど) のLLMへのトランスポート。会話をRole付きの`messages`として受け取り, モデルの出力を返す。
ロールごとのタグ付け (chat template) はOpenAI互換サーバが行う想定なので, 実装側では行わない。

### `complete`

```scala
def complete(messages: Vector[Message]): IO[String]
```

**Parameters:**

- **messages** (`Vector[Message]`) — 会話全体 (system promptを含む)

**Returns:**

- `IO[String]` — モデルの出力全体

### `stream`

```scala
def stream(messages: Vector[Message]): Stream[IO, String]
```

**Parameters:**

- **messages** (`Vector[Message]`) — 会話全体

**Returns:**

- `Stream[IO, String]` — 出力の差分を到着順に流す。デフォルト実装は`complete`の結果を1つの差分として流す (ストリーミング未対応の実装向け)

**Example: 独自のLLMServer**

```scala
// 送る前に会話をログに出すLLMServer
class LoggingLLMServer(underlying: LLMServer) extends LLMServer {
  def complete(messages: Vector[Message]): IO[String] =
    IO.println(s"-> ${messages.length} messages") *> underlying.complete(messages)

  override def stream(messages: Vector[Message]): Stream[IO, String] =
    Stream.exec(IO.println(s"-> ${messages.length} messages")) ++ underlying.stream(messages)
}

OpenAILLMServer.resource(config).flatMap { server =>
  Agent.from(new LoggingLLMServer(server), new WeatherTool)
}
```

---

## `LLMServer.apply`

```scala
object LLMServer {
  def apply(run: Vector[Message] => IO[String]): LLMServer
}
```

関数から`LLMServer`を作る。テストやモック用。`stream`はデフォルト実装 (1つの差分) になる。

**Parameters:**

- **run** (`Vector[Message] => IO[String]`) — `complete`の実装

**Returns:**

- `LLMServer`

**Example:**

```scala
val echo: LLMServer =
  LLMServer(messages => IO.pure(s"echo: ${messages.last.content}"))
```

順番に出力を返すモックは[モックLLMServerでテストする](../examples/testing/mock_llm_server.md)を参照。

---

## `LLMServerConfig`

```scala
case class LLMServerConfig(
  baseUrl: String,
  model: String,
  apiKey: Option[String] = None,
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  timeout: Duration = Duration.ofSeconds(120)
)
```

[`OpenAILLMServer`](#openaillmserver)の設定。

**Parameters:**

- **baseUrl** (`String`) — サーバのURL。`/v1/chat/completions`はライブラリが付ける (末尾の`/`は無視する)
- **model** (`String`) — リクエストの`model`
- **apiKey** (`Option[String]`, default: `None`) — 指定すると`Authorization: Bearer <apiKey>`を付ける
- **temperature** (`Option[Double]`, default: `None`) — 指定するとリクエストの`temperature`に入れる
- **maxTokens** (`Option[Int]`, default: `None`) — 指定するとリクエストの`max_tokens`に入れる
- **timeout** (`java.time.Duration`, default: 120秒) — 1リクエストのタイムアウト。ストリーミングではレスポンスヘッダが返るまでの時間

**Example:**

=== "Ollama"

    ```scala
    LLMServerConfig(baseUrl = "http://localhost:11434", model = "gemma4:e2b")
    ```

=== "vLLM"

    ```scala
    // vllm serve Qwen/Qwen3-8B
    LLMServerConfig(baseUrl = "http://localhost:8000", model = "Qwen/Qwen3-8B")
    ```

=== "OpenAI"

    ```scala
    LLMServerConfig(
      baseUrl = "https://api.openai.com",
      model = "gpt-4o-mini",
      apiKey = sys.env.get("OPENAI_API_KEY"),
      temperature = Some(0.0)
    )
    ```

---

## `OpenAILLMServer`

```scala
class OpenAILLMServer(
  val config: LLMServerConfig,
  val httpClient: HttpClient = HttpClient.newHttpClient(),
  val roleName: Role => String = OpenAILLMServer.defaultRoleName
) extends LLMServer
```

**Bases:** [`LLMServer`](#llmserver)

OpenAI互換の`POST {baseUrl}/v1/chat/completions`を呼ぶ実装。Toolの説明や呼び出しはプロンプトの中で行い, OpenAIの`tools`パラメータは使わない。

**Parameters:**

- **config** ([`LLMServerConfig`](#llmserverconfig)) — 接続先とモデル
- **httpClient** (`java.net.http.HttpClient`, default: 新規) — 使うHTTPクライアント
- **roleName** (`Role => String`, default: [`defaultRoleName`](#roleとopenai-roleの対応)) — `Role`をOpenAIのrole名に変換する

### `complete`

```scala
def complete(messages: Vector[Message]): IO[String]
```

`stream: false`で送り, `choices[0].message.content`を返す。

**Parameters:**

- **messages** (`Vector[Message]`) — 会話全体

**Returns:**

- `IO[String]` — `choices[0].message.content`

**エラー:**

- `RuntimeException("LLMServer error <status>: <body>")` — 2xx以外のステータス
- `RuntimeException("Unexpected LLMServer response: ...")` — レスポンスに`choices[0].message.content` (文字列) がない
- `java.net.http.HttpTimeoutException` などの通信エラー

### `stream`

```scala
override def stream(messages: Vector[Message]): Stream[IO, String]
```

`stream: true`で送り, SSEの各行 (`data: {...}`) から`choices[0].delta.content`を取り出して流す。空の差分と`data: [DONE]`は流さない。
Streamが終わる (またはキャンセルされる) とレスポンスのbodyを閉じる。

**Parameters:**

- **messages** (`Vector[Message]`) — 会話全体

**Returns:**

- `Stream[IO, String]` — 本文の差分

**エラー:**

- `RuntimeException("LLMServer error <status>: <body>")` — 2xx以外のステータス

### リクエストの形

```json
{
  "model": "<config.model>",
  "messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}],
  "stream": false,
  "temperature": 0.0,
  "max_tokens": 512
}
```

`temperature` / `max_tokens`は`LLMServerConfig`で指定したときだけ入る。

### `OpenAILLMServer.resource`

```scala
object OpenAILLMServer {
  def resource(config: LLMServerConfig): Resource[IO, OpenAILLMServer]
}
```

`OpenAILLMServer`を作り, 解放時に`HttpClient`を閉じる`Resource`。`Agent.create`は内部でこれを使う。

**Parameters:**

- **config** ([`LLMServerConfig`](#llmserverconfig))

**Returns:**

- `Resource[IO, OpenAILLMServer]`

**Example:**

```scala
OpenAILLMServer.resource(config).use { server =>
  server.complete(Vector(Message(Role.User, "hi"))).flatMap(IO.println)
}
```

### RoleとOpenAI roleの対応

```scala
object OpenAILLMServer {
  def defaultRoleName(role: Role): String
}
```

| `Role` | role | 備考 |
| --- | --- | --- |
| `Role.System` | `system` | |
| `Role.User` | `user` | |
| `Role.Assistant` | `assistant` | |
| `Role.Thinking` | `assistant` | 専用のroleがないため |
| `Role.Tool` | `user` | OpenAIの`tools` APIを使わないので, `tool_call_id`が要らない`user`に載せる |

`roleName`を差し替えると変更できる。

```scala
// Toolの結果を tool ロールで送るサーバ向け
new OpenAILLMServer(
  config,
  roleName = {
    case Role.Tool => "tool"
    case other     => OpenAILLMServer.defaultRoleName(other)
  }
)
```
