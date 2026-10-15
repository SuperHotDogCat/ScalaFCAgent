# Reference: コア (lib)

`lib`モジュールが提供するFunction Callingの中核。HTTPサーバなどの入口は[HTTPサーバのリファレンス](./http-server.md)を参照。

## パッケージ構成

| パッケージ | 内容 |
| --- | --- |
| `org.scalafcagent.tool` | ToolとTool呼び出し |
| `org.scalafcagent.chat` | 会話 (Role / Message / Chat) |
| `org.scalafcagent.llm` | LLMServer (OpenAI互換) |
| `org.scalafcagent.client` | 送受信ループ |
| `org.scalafcagent.agent` | 公開API |

全体の流れ:

```
User → Agent → Client ──(messages)──▶ LLMServer ──▶ OpenAI互換API
                   │                        │
                   │◀────── output ─────────┘
                   ▼
                 Chat ──▶ ToolEngine ──▶ Tool
                   ▲                        │
                   └──── <tool_result> ◀────┘
```

---

## Tool

### `ToolCall`

```scala
case class ToolCall(
  functionName: String,
  arguments: String // JSON文字列
)
```

### `ToolResult`

Toolの実行結果。

```scala
sealed trait ToolResult

object ToolResult {
  case class Success(value: String) extends ToolResult
  case class Error(message: String) extends ToolResult
}
```

### `ToolError`

```scala
case class ToolError(message: String)
```

### `Schema[A]`

引数の型`A`とJSON文字列の相互変換。circeの`Decoder` / `Encoder`を実装する。今のところ手入力。

```scala
trait Schema[A] {
  implicit val decoder: Decoder[A]
  implicit val encoder: Encoder[A]

  def encode(input: A): String
  def decode(input: String): Either[ToolError, A]
}
```

### `Tool`

ToolEngineから扱うための型消去されたTool。

```scala
trait Tool {
  def name: String
  def description: String
  def argumentJSONSchema: String
  def execute(input: String): IO[ToolResult]
}
```

### `TypedTool[A]`

型付きのTool。`execute`は`schema.decode`で引数を型安全に変換してから`run`を呼ぶ。

```scala
trait TypedTool[A] extends Tool {
  def schema: Schema[A]
  def run(args: A): IO[ToolResult]

  final override def execute(input: String): IO[ToolResult]
}
```

`decode`に失敗した場合は`ToolResult.Error`を返す。

### `ToolEngine`

名前からToolを引いて実行する。

```scala
class ToolEngine(val tools: Tool*) {
  def call(toolCall: ToolCall): IO[ToolResult]
}
```

未登録の名前を渡すと`ToolResult.Error("Unknown tool: <name>")`を返す。

---

## Chat

### `Role`

会話の役割。OpenAI互換APIのroleへの写像はLLMServer側で行う(後述)。

```scala
sealed trait Role

object Role {
  case object System extends Role
  case object User extends Role
  case object Assistant extends Role
  case object Thinking extends Role
  case object Tool extends Role
}
```

### `Message`

```scala
case class Message(role: Role, content: String)
```

### `ChatFormat`

モデルごとに差し替える設定。OpenAI互換サーバが面倒を見てくれない**Tool呼び出しの形式だけ**をここに置く。ロールのタグ付け(chat template)はサーバが行うため、ここにはない。

```scala
case class ChatFormat(
  // LLMの出力からTool呼び出しの一部を取り出す正規表現 (group(1)をdecodeToolCallに渡す)
  toolCallPattern: Regex = ChatFormat.defaultToolCallPattern,
  // 取り出した文字列をToolCallに変換する
  decodeToolCall: String => Option[ToolCall] = ChatFormat.defaultDecodeToolCall,
  // 登録されたToolをsystem promptに載せる文言に変換する
  renderTools: Vector[Tool] => String = ChatFormat.defaultRenderTools,
  // Toolの実行結果を会話に載せるときの形式
  renderToolResult: String => String = ChatFormat.defaultRenderToolResult
)
```

- `ChatFormat.Default` — XMLの`<tool>` / `<tool_result>`(後述)
- `ChatFormat.defaultRenderToolResult` — `<tool_result>\n...\n</tool_result>`

### `Chat`

実行中の会話。immutableで, 追加は新しい`Chat`を返す。

```scala
class Chat(
  val toolEngine: ToolEngine = new ToolEngine,
  val messages: Vector[Message] = Vector.empty,
  val format: ChatFormat = ChatFormat.Default
) {

  // 追加 (新しいChatを返す)
  def append(message: Message): Chat
  def appendAll(additional: Vector[Message]): Chat
  def system(content: String): Chat
  def user(content: String): Chat
  def assistant(content: String): Chat
  def thinking(content: String): Chat
  def tool(content: String): Chat

  // Tool
  def toolCalls(output: String): Vector[ToolCall]
  def runToolCalls(output: String): IO[Chat]
  def callTool(toolCall: ToolCall): IO[Chat]
}
```

`runToolCalls`は出力に含まれるTool呼び出しを順に実行し, 結果を`Role.Tool`のメッセージとして追加する。

---

## LLM

### `LLMServer`

外部のOpenAI互換APIを想定したトランスポート。会話は`Role`付きの`messages`として渡す。

```scala
trait LLMServer {
  // 1回の出力をまとめて返す
  def complete(messages: Vector[Message]): IO[String]

  // 本文の差分を到着順に流す
  // デフォルトはcompleteを1つの差分として返す (ストリーミング未対応の実装向け)
  def stream(messages: Vector[Message]): Stream[IO, String] =
    Stream.eval(complete(messages))
}

object LLMServer {
  // テストやモック用
  def apply(run: Vector[Message] => IO[String]): LLMServer
}
```

### `LLMServerConfig`

トランスポートの設定。

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

### `OpenAILLMServer`

`POST {baseUrl}/v1/chat/completions` に`messages`を送り, `choices[0].message.content`を返す。`stream`は`stream: true`で送り, SSEの`choices[0].delta.content`を差分として流す。

```scala
class OpenAILLMServer(
  val config: LLMServerConfig,
  val httpClient: HttpClient = HttpClient.newHttpClient(),
  val roleName: Role => String = OpenAILLMServer.defaultRoleName
) extends LLMServer {

  def complete(messages: Vector[Message]): IO[String]

  override def stream(messages: Vector[Message]): Stream[IO, String]
}

object OpenAILLMServer {
  def defaultRoleName(role: Role): String
  def resource(config: LLMServerConfig): Resource[IO, OpenAILLMServer]
}
```

`roleName`を差し替えるとroleの写像を変更できる。

### RoleとOpenAI roleの対応

| `Role` | role | 備考 |
| --- | --- | --- |
| `Role.System` | `system` | |
| `Role.User` | `user` | |
| `Role.Assistant` | `assistant` | |
| `Role.Thinking` | `assistant` | 専用roleが無いため |
| `Role.Tool` | `user` | ネイティブのtools APIを使わないため`tool_call_id`が不要な`user`に載せる |

---

## Client

### `Client`

UserとChatからのリクエストをLLMServerへ送り, Tool呼び出しを解決して繰り返す。

```scala
class Client(
  val llmServer: LLMServer,
  val chat: Ref[IO, Chat],
  val initialChat: Chat,
  val maxToolCalls: Int = 8
) {

  // Userからの1ターン. Tool解決まで含めた最終出力を返す
  def send(userMessage: String): IO[String]

  // Userからの1ターン. Tool解決まで含めた差分を到着順に流す
  def sendStream(userMessage: String): Stream[IO, String]

  // 会話 (messages全体) を渡して1ターン実行する. 会話は初期状態に置き換える
  def send(messages: Vector[Message]): IO[String]

  def sendStream(messages: Vector[Message]): Stream[IO, String]

  // 実行中の会話
  def history: IO[Vector[Message]]

  // 会話を初期状態 (system promptなど) に戻す
  def reset: IO[Unit]
}
```

`maxToolCalls`を超えてTool呼び出しが続くとエラーになる。

`send(messages)` / `sendStream(messages)`は, 会話を初期状態 (Toolの使い方のsystem promptなど) の後ろに`messages`を載せたものに置き換えてから1ターン実行する。履歴を毎回受け取るOpenAI互換APIのような用途向け。

### `Client.create`

```scala
object Client {
  def create(
    llmServer: LLMServer,
    chat: Chat = new Chat,
    maxToolCalls: Int = 8
  ): IO[Client]
}
```

---

## Agent

UserとLLMServerの間を仲介する公開API。1つの`Agent`が1つの会話に対応する。

### `AgentConfig`

Agentの振る舞いの設定。

```scala
case class AgentConfig(
  format: ChatFormat = ChatFormat.Default,
  systemPrompt: Option[String] = None,
  maxToolCalls: Int = 8
)
```

### `Agent`

```scala
class Agent(val client: Client) {
  def send(message: String): IO[String]
  def sendStream(message: String): Stream[IO, String]

  // 会話 (messages全体) を渡して1ターン実行する
  def send(messages: Vector[Message]): IO[String]
  def sendStream(messages: Vector[Message]): Stream[IO, String]

  def history: IO[Vector[Message]]
  def reset: IO[Unit]
}
```

`sendStream`はToolを呼ぶターンも含めて差分を流す。ただしTool呼び出しの出力 (`<tool>...</tool>`) も本文の一部としてそのまま流れる点に注意 (Toolの実行結果はモデルにだけ返す)。

### `Agent.create` / `Agent.from`

`create`は内部で`OpenAILLMServer`を作る。`from`は`LLMServer`を差し替える(テストや独自トランスポート用)。

```scala
object Agent {
  def create(llm: LLMServerConfig, tools: Tool*): Resource[IO, Agent]
  def create(llm: LLMServerConfig, config: AgentConfig, tools: Tool*): Resource[IO, Agent]

  def from(llmServer: LLMServer, tools: Tool*): Resource[IO, Agent]
  def from(llmServer: LLMServer, config: AgentConfig, tools: Tool*): Resource[IO, Agent]
}
```

### 初期system prompt

`Agent.create` / `Agent.from`は, 登録されたToolがあればその使い方を初期のsystem promptに自動で載せる。

- `config.systemPrompt` (あれば) と, `format.renderTools(tools)` を改行2つで連結する
- Toolが無く`systemPrompt`も無ければ, systemメッセージは作らない (普通の会話になる)

---

## Tool呼び出しの形式

デフォルト (`ChatFormat.Default`) はXML形式。READMEの方針どおり。

LLMに出させる呼び出し:

```
<tool>
{
    "function_name": String,
    "arguments": String (JSON)
}
</tool>
```

`arguments`はJSON文字列でもJSONオブジェクトでも受け付ける (モデルがどちらで出すことがあるため)。

LLMへ戻す結果:

```
<tool_result>
String
</tool_result>
```

モデルごとに形式が違う場合は`ChatFormat`を組み立てて差し替える(例は`lib/src/test/scala/org/example/ChatFormat.scala`)。例:

- Hermes系 (`<tool_call>` / `<tool_response>`)
- Qwen3-Coder (XMLの`<function=...>`)
- DeepSeek V3.1 (特殊トークン)
- Gemma 3 (Python風の`tool_code`ブロック)
