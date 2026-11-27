# org.scalafcagent.agent

UserとLLMServerの間を仲介する公開API。

```scala
import org.scalafcagent.agent.{Agent, AgentConfig}
```

| 名前 | 種類 | 内容 |
| --- | --- | --- |
| [`Agent`](#agent) | class | 1つの会話に対応するエージェント |
| [`Agent.create`](#agentcreate) | method | `LLMServerConfig`から作る |
| [`Agent.from`](#agentfrom) | method | 任意の`LLMServer`から作る |
| [`AgentConfig`](#agentconfig) | case class | Agentの振る舞いの設定 |

---

## `AgentConfig`

```scala
case class AgentConfig(
  format: ChatFormat = ChatFormat.Default,
  systemPrompt: Option[String] = None,
  maxToolCalls: Int = 8
)
```

Agentの振る舞いの設定。接続先などトランスポートの設定は[`LLMServerConfig`](./llm.md#llmserverconfig)に分けている。

**Parameters:**

- **format** (`ChatFormat`, default: `ChatFormat.Default`) — Tool呼び出しの形式。デフォルトは`<tool>` / `<tool_result>`のXML形式。[ChatFormat](./chat.md#chatformat)を参照
- **systemPrompt** (`Option[String]`, default: `None`) — 追加するsystem prompt。Toolの使い方の説明の**前**に置かれる
- **maxToolCalls** (`Int`, default: `8`) — 1ターンでLLMServerへ送るリクエストの上限。`maxToolCalls`回送っても出力にTool呼び出しが残っていると, `send`は失敗する

**Example:**

```scala
val config = AgentConfig(
  systemPrompt = Some("あなたは日本語で簡潔に答えるアシスタントです。"),
  maxToolCalls = 4
)

Agent.create(llm, config, new WeatherTool)
```

---

## `Agent`

```scala
class Agent(val client: Client)
```

UserとLLMServerの間を仲介する。1つの`Agent`が1つの会話 (履歴) を持つ。
通常はコンストラクタではなく[`Agent.create`](#agentcreate) / [`Agent.from`](#agentfrom)で作る。

**Parameters:**

- **client** ([`Client`](./client.md#client)) — 送受信ループの実体。`Agent`のメソッドはすべてこれに委譲する

**Methods:**

| メソッド | 戻り値 | 内容 |
| --- | --- | --- |
| [`send(message)`](#send) | `IO[String]` | 1ターン実行し, Tool解決後の最終出力を返す |
| [`sendStream(message)`](#sendstream) | `Stream[IO, String]` | 1ターン実行し, 出力の差分を流す |
| [`send(messages)`](#send-messages) | `IO[String]` | 会話全体を渡して1ターン実行する |
| [`sendStream(messages)`](#sendstream-messages) | `Stream[IO, String]` | 会話全体を渡して1ターン実行し, 差分を流す |
| [`history`](#history) | `IO[Vector[Message]]` | 現在の会話 |
| [`reset`](#reset) | `IO[Unit]` | 会話を初期状態に戻す |

### `send`

```scala
def send(message: String): IO[String]
```

Userの発話を会話に追加し, LLMServerへ送る。出力にTool呼び出しがあればToolを実行して結果を会話に追加し, Tool呼び出しがなくなるまで繰り返す。

**Parameters:**

- **message** (`String`) — Userの発話

**Returns:**

- `IO[String]` — Tool呼び出しを含まない最後のモデル出力。モデルが出力した文字列そのもの (thinkingのタグなどは除去しない)

**エラー:**

- `RuntimeException("LLMServer error <status>: <body>")` — LLMServerが2xx以外を返した
- `RuntimeException("Tool call limit exceeded: <maxToolCalls>")` — Tool呼び出しが上限まで続いた
- Toolの`run`が返した`IO`の失敗はそのまま伝わる (`ToolResult.Error`はエラーにならず, モデルに返る)

途中で失敗しても, それまでの往復 (Tool呼び出しと結果) は`history`に残る。

**Example:**

```scala
Agent.create(llm, new WeatherTool).use { agent =>
  for {
    a <- agent.send("東京の天気は?")
    b <- agent.send("じゃあ大阪は?") // 前のターンの履歴を引き継ぐ
    _ <- IO.println(a) *> IO.println(b)
  } yield ()
}
```

### `sendStream`

```scala
def sendStream(message: String): Stream[IO, String]
```

[`send`](#send)のストリーミング版。LLMServerの出力の差分を到着順に流す。Toolを呼ぶターンでは, その差分を流し終えてからToolを実行し, 次のリクエストの差分へ続ける。

**Parameters:**

- **message** (`String`) — Userの発話

**Returns:**

- `Stream[IO, String]` — 出力の差分。Tool呼び出し (`<tool>...</tool>`) も本文の差分として流れる。Toolの結果 (`<tool_result>`) は流れない

**エラー:** [`send`](#send)と同じ。Streamの失敗として現れる。

**Example:**

```scala
agent.sendStream("今の東京の時刻は?")
  .evalMap(chunk => IO.print(chunk))
  .compile
  .drain
```

### `send` (messages)

```scala
def send(messages: Vector[Message]): IO[String]
```

会話全体を渡して1ターン実行する。会話を「初期状態 (system prompt) + `messages`」に**置き換えて**から[`send`](#send)と同じ処理を行う。
クライアントが毎回履歴を送る, OpenAI互換APIのようなステートレスな用途向け。

**Parameters:**

- **messages** (`Vector[Message]`) — 会話全体。最後は通常`Role.User`

**Returns:**

- `IO[String]` — 最後のモデル出力

**エラー:** [`send`](#send)と同じ。

**Example:**

```scala
import org.scalafcagent.chat.{Message, Role}

agent.send(Vector(
  Message(Role.User, "今の東京の時刻を教えて"),
  Message(Role.Assistant, "15:04です。"),
  Message(Role.User, "ニューヨークは?")
))
```

### `sendStream` (messages)

```scala
def sendStream(messages: Vector[Message]): Stream[IO, String]
```

[`send(messages)`](#send-messages)のストリーミング版。

**Returns:**

- `Stream[IO, String]` — 出力の差分 ([`sendStream`](#sendstream)と同じ)

### `history`

```scala
def history: IO[Vector[Message]]
```

現在の会話を返す。

**Returns:**

- `IO[Vector[Message]]` — 古い順のメッセージ。Toolを登録しているか`systemPrompt`があれば, 先頭は`Role.System`。Tool呼び出しを含むモデル出力は`Role.Assistant`, Toolの結果は`Role.Tool`として入る

**Example:**

```scala
for {
  _       <- agent.send("東京の天気は?")
  history <- agent.history
  _       <- IO.println(history.map(_.role))
  // Vector(System, User, Assistant, Tool, Assistant)
} yield ()
```

### `reset`

```scala
def reset: IO[Unit]
```

会話を初期状態 (system promptのみ) に戻す。登録したToolと設定はそのまま。

**Returns:**

- `IO[Unit]`

---

## `Agent.create`

```scala
object Agent {
  def create(llm: LLMServerConfig, tools: Tool*): Resource[IO, Agent]
  def create(llm: LLMServerConfig, config: AgentConfig, tools: Tool*): Resource[IO, Agent]
}
```

`LLMServerConfig`から[`OpenAILLMServer`](./llm.md#openaillmserver)を作り, Toolを登録したAgentを作る。

**Parameters:**

- **llm** ([`LLMServerConfig`](./llm.md#llmserverconfig)) — 接続先とモデル
- **config** ([`AgentConfig`](#agentconfig), default: `AgentConfig()`) — Agentの設定
- **tools** (`Tool*`) — 登録するTool。0個でもよい (普通の会話になる)

**Returns:**

- `Resource[IO, Agent]` — `use`を抜けると`OpenAILLMServer`の`HttpClient`が閉じられる

**初期のsystem prompt:**

`config.systemPrompt`と`config.format.renderTools(tools)`を空行で連結したものが, systemメッセージとして会話の先頭に入る。
どちらも空なら (Toolなし・systemPromptなし), systemメッセージは作らない。

**Example:**

```scala
import cats.effect.{IO, IOApp}
import org.scalafcagent.agent.Agent
import org.scalafcagent.llm.LLMServerConfig

object Main extends IOApp.Simple {
  private val llm = LLMServerConfig(baseUrl = "http://localhost:11434", model = "gemma4:e2b")

  def run: IO[Unit] =
    Agent.create(llm, new CurrentTimeTool, new WeatherTool).use { agent =>
      agent.send("今の東京の時刻と天気を教えて").flatMap(IO.println)
    }
}
```

---

## `Agent.from`

```scala
object Agent {
  def from(llmServer: LLMServer, tools: Tool*): Resource[IO, Agent]
  def from(llmServer: LLMServer, config: AgentConfig, tools: Tool*): Resource[IO, Agent]
}
```

任意の[`LLMServer`](./llm.md#llmserver)からAgentを作る。テスト用のモックや, OpenAI互換以外のトランスポートを使う場合に使う。

**Parameters:**

- **llmServer** ([`LLMServer`](./llm.md#llmserver)) — 使うLLMServer。Agentは解放しない (所有権は呼び出し側)
- **config** ([`AgentConfig`](#agentconfig), default: `AgentConfig()`) — Agentの設定
- **tools** (`Tool*`) — 登録するTool

**Returns:**

- `Resource[IO, Agent]` — 解放時に何もしない

**Example:**

```scala
import org.scalafcagent.llm.LLMServer

val echo = LLMServer(messages => IO.pure(s"echo: ${messages.last.content}"))

Agent.from(echo).use(_.send("hi")) // IO("echo: hi")
```

詳しくは[モックLLMServerでテストする](../examples/testing/mock_llm_server.md)を参照。
