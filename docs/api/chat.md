# org.scalafcagent.chat

会話の表現とTool呼び出しの形式。

```scala
import org.scalafcagent.chat.{Chat, ChatFormat, Message, Role}
```

| 名前 | 種類 | 内容 |
| --- | --- | --- |
| [`Role`](#role) | sealed trait | 会話の役割 |
| [`Message`](#message) | case class | Role付きのメッセージ |
| [`ChatFormat`](#chatformat) | case class | Tool呼び出しの形式 |
| [`Chat`](#chat) | class | immutableな会話 |

---

## `Role`

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

会話の役割。OpenAI互換APIのrole名への変換は[`OpenAILLMServer`](./llm.md#roleとopenai-roleの対応)が行う。

| 値 | 内容 |
| --- | --- |
| `Role.System` | system prompt (Toolの使い方を含む) |
| `Role.User` | Userの発話 |
| `Role.Assistant` | モデルの出力 (Tool呼び出しを含むものも) |
| `Role.Thinking` | モデルの思考。ライブラリ自身は追加しない |
| `Role.Tool` | Toolの実行結果 (`<tool_result>...</tool_result>`) |

---

## `Message`

```scala
case class Message(role: Role, content: String)
```

**Parameters:**

- **role** ([`Role`](#role)) — 役割
- **content** (`String`) — 本文

**Example:**

```scala
Message(Role.User, "東京の天気は?")
```

---

## `ChatFormat`

```scala
case class ChatFormat(
  toolCallPattern: Regex = ChatFormat.defaultToolCallPattern,
  decodeToolCall: String => Option[ToolCall] = ChatFormat.defaultDecodeToolCall,
  renderTools: Vector[Tool] => String = ChatFormat.defaultRenderTools,
  renderToolResult: String => String = ChatFormat.defaultRenderToolResult
)
```

Tool呼び出しの形式。ロールごとのタグ付け (chat template) はOpenAI互換サーバが行うので, ここにはTool呼び出しに関するものだけを置く。
[`AgentConfig.format`](./agent.md#agentconfig)に渡して使う。

**Parameters:**

- **toolCallPattern** (`Regex`) — モデル出力からTool呼び出しを取り出す正規表現。マッチごとの`group(1)`が`decodeToolCall`に渡る。デフォルトは`(?s)<tool>\s*(\{.*?\})\s*</tool>`
- **decodeToolCall** (`String => Option[ToolCall]`) — 取り出した文字列を[`ToolCall`](./tool.md#toolcall)にする。`None`を返したものは無視される。デフォルトは`{"function_name": ..., "arguments": ...}`を読む (`arguments`は文字列でもオブジェクトでもよい)
- **renderTools** (`Vector[Tool] => String`) — 登録したToolをsystem promptの文言にする。空文字列を返すとsystem promptに何も足さない
- **renderToolResult** (`String => String`) — Toolの結果を会話に載せる形式。デフォルトは`<tool_result>\n...\n</tool_result>`

**Attributes / Methods (companion):**

| 名前 | 型 | 内容 |
| --- | --- | --- |
| `ChatFormat.Default` | `ChatFormat` | すべてデフォルトの形式 (`ChatFormat()`) |
| `ChatFormat.defaultRenderTools(tools)` | `String` | `## Tool Use`と`## Available Tools`の説明を作る。Toolが0個なら空文字列 |
| `ChatFormat.defaultRenderToolResult(value)` | `String` | `<tool_result>\nvalue\n</tool_result>` |

### デフォルトの形式

モデルに出させる呼び出し:

```
<tool>
{"function_name": "<tool name>", "arguments": "<arguments as a JSON string>"}
</tool>
```

モデルへ返す結果:

```
<tool_result>
<ToolResult.Success の value, または Error: <message>>
</tool_result>
```

`defaultRenderTools`がsystem promptに載せる文言:

````
## Tool Use

You can use the following tools. To call a tool, output:

<tool>
{"function_name": "<tool name>", "arguments": "<arguments as a JSON string>"}
</tool>

## Available Tools

<tool>
{
  "name" : "weather",
  "description" : "指定した都市の現在の天気と気温を返す",
  "parameters" : {
    "type" : "object",
    ...
  }
}
</tool>
````

**Example:**

```scala
// Hermes形式 (<tool_call> / <tool_response>)
val hermes = ChatFormat(
  toolCallPattern = """(?s)<tool_call>\s*(\{.*?\})\s*</tool_call>""".r,
  decodeToolCall = decodeHermesCall,
  renderToolResult = value => s"<tool_response>\n$value\n</tool_response>"
)

Agent.create(llm, AgentConfig(format = hermes), new WeatherTool)
```

モデル別の例は[モデルごとのTool呼び出し形式](../examples/features/custom_chat_format.md)を参照。

---

## `Chat`

```scala
class Chat(
  val toolEngine: ToolEngine = new ToolEngine,
  val messages: Vector[Message] = Vector.empty,
  val format: ChatFormat = ChatFormat.Default
)
```

実行中の会話。immutableで, 追加するメソッドは新しい`Chat`を返す。通常は[`Client`](./client.md#client)が内部で使う。

**Parameters:**

- **toolEngine** ([`ToolEngine`](./tool.md#toolengine), default: 空) — Tool呼び出しの実行に使う
- **messages** (`Vector[Message]`, default: 空) — 会話
- **format** ([`ChatFormat`](#chatformat), default: `ChatFormat.Default`) — Tool呼び出しの形式

**Methods:**

| メソッド | 戻り値 | 内容 |
| --- | --- | --- |
| `append(message)` | `Chat` | メッセージを1つ追加する |
| `appendAll(messages)` | `Chat` | メッセージをまとめて追加する |
| `system(content)` / `user(content)` / `assistant(content)` / `thinking(content)` / `tool(content)` | `Chat` | 対応するRoleのメッセージを追加する |
| [`toolCalls(output)`](#toolcalls) | `Vector[ToolCall]` | 出力からTool呼び出しを取り出す |
| [`runToolCalls(output)`](#runtoolcalls) | `IO[Chat]` | 出力のTool呼び出しを順に実行し, 結果を追加する |
| [`callTool(toolCall)`](#calltool) | `IO[Chat]` | Tool呼び出しを1つ実行し, 結果を追加する |

**Example:**

```scala
val chat =
  new Chat()
    .system("You are helpful.")
    .user("hi")
    .assistant("Hello!")

chat.messages.map(_.role) // Vector(System, User, Assistant)
```

### `toolCalls`

```scala
def toolCalls(output: String): Vector[ToolCall]
```

`format.toolCallPattern`にマッチした部分を, 出現順に`format.decodeToolCall`で変換する。

**Parameters:**

- **output** (`String`) — モデルの出力

**Returns:**

- `Vector[ToolCall]` — 取り出したTool呼び出し。decodeできなかったものは含まれない

**Example:**

```scala
val output =
  """<tool>
    |{"function_name": "add", "arguments": {"a": 1, "b": 2}}
    |</tool>""".stripMargin

new Chat().toolCalls(output)
// Vector(ToolCall("add", "{\"a\":1,\"b\":2}"))
```

### `runToolCalls`

```scala
def runToolCalls(output: String): IO[Chat]
```

`toolCalls(output)`を順に (並列ではなく) 実行し, それぞれの結果を`Role.Tool`のメッセージとして追加する。出力自体 (`Role.Assistant`) は追加しない。

**Parameters:**

- **output** (`String`) — モデルの出力

**Returns:**

- `IO[Chat]` — 結果を追加した新しい`Chat`。Tool呼び出しがなければ自分自身

**Example:**

```scala
val chat = new Chat(toolEngine = new ToolEngine(new AddTool))

chat.runToolCalls(output).map(_.messages)
// IO(Vector(Message(Tool, "<tool_result>\n3.0\n</tool_result>")))
```

### `callTool`

```scala
def callTool(toolCall: ToolCall): IO[Chat]
```

**Parameters:**

- **toolCall** ([`ToolCall`](./tool.md#toolcall)) — 実行するTool呼び出し

**Returns:**

- `IO[Chat]` — 結果を`format.renderToolResult`で整形し, `Role.Tool`として追加した`Chat`。`ToolResult.Error(m)`は`Error: m`として整形される
