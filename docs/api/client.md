# org.scalafcagent.client

送受信ループ。[`Agent`](./agent.md#agent)の実体で, 通常は`Agent`経由で使う。

```scala
import org.scalafcagent.client.Client
```

| 名前 | 種類 | 内容 |
| --- | --- | --- |
| [`Client`](#client) | class | LLMServerへの送信とTool解決の繰り返し |
| [`Client.create`](#clientcreate) | method | `Client`を作る |

---

## `Client`

```scala
class Client(
  val llmServer: LLMServer,
  val chat: Ref[IO, Chat],
  val initialChat: Chat,
  val maxToolCalls: Int = 8
)
```

会話を`Ref`に持ち, LLMServerへの送信とTool呼び出しの解決を繰り返す。

**Parameters:**

- **llmServer** ([`LLMServer`](./llm.md#llmserver)) — 送信先
- **chat** (`Ref[IO, Chat]`) — 現在の会話
- **initialChat** ([`Chat`](./chat.md#chat)) — `reset`や`send(messages)`で戻す初期状態 (system prompt)
- **maxToolCalls** (`Int`, default: `8`) — 1ターンでLLMServerへ送るリクエストの上限

**Methods:**

| メソッド | 戻り値 | 内容 |
| --- | --- | --- |
| `send(userMessage: String)` | `IO[String]` | 1ターン実行し, 最終出力を返す |
| `sendStream(userMessage: String)` | `Stream[IO, String]` | 1ターン実行し, 差分を流す |
| `send(messages: Vector[Message])` | `IO[String]` | 会話を初期状態 + `messages`に置き換えて1ターン実行する |
| `sendStream(messages: Vector[Message])` | `Stream[IO, String]` | 同上のストリーミング版 |
| `history` | `IO[Vector[Message]]` | 現在の会話 |
| `reset` | `IO[Unit]` | 会話を`initialChat`に戻す |

各メソッドの引数・戻り値・エラーは[`Agent`](./agent.md#agent)の同名メソッドと同じ。

### 1ターンの処理

`send`は次の手順で動く (`sendStream`も同じで, 2の出力を差分として流しながら溜める)。

1. 会話に`Role.User`のメッセージを追加する
2. 会話全体を`llmServer.complete`に送る
3. 出力を`Role.Assistant`として追加し, `chat.runToolCalls(output)`でToolを実行して結果を`Role.Tool`として追加する
4. 出力にTool呼び出しがあれば2に戻る。なければ出力を返す

2を`maxToolCalls`回行っても出力にTool呼び出しが残っていると, `RuntimeException("Tool call limit exceeded: <maxToolCalls>")`で失敗する。
各ステップの後で`chat`を更新するので, 失敗してもそこまでの会話は残る。

!!! note "並行に呼んだ場合"
    `chat`は`Ref`だが, 1ターンの間ロックはしない。同じ`Client` (`Agent`) に対して`send`を並行に呼ぶと会話が混ざる。
    並行に処理したい場合は, リクエストごとに`Agent`を作る (`server`モジュールはそうしている)。

---

## `Client.create`

```scala
object Client {
  def create(
    llmServer: LLMServer,
    chat: Chat = new Chat,
    maxToolCalls: Int = 8
  ): IO[Client]
}
```

`chat`を初期状態とする`Client`を作る。

**Parameters:**

- **llmServer** ([`LLMServer`](./llm.md#llmserver)) — 送信先
- **chat** ([`Chat`](./chat.md#chat), default: 空) — 初期の会話。`initialChat`にもなる
- **maxToolCalls** (`Int`, default: `8`) — 1ターンのリクエスト上限

**Returns:**

- `IO[Client]` — `Ref`を作るため`IO`で返る

**Example:**

`Agent.from`はsystem promptの組み立てまで行うが, `Client`を直接使うと初期の会話を自由に組める。

```scala
import org.scalafcagent.chat.Chat
import org.scalafcagent.tool.ToolEngine

val initial =
  new Chat(toolEngine = new ToolEngine(new WeatherTool))
    .system("Use <tool> to call tools. ...")
    .user("前提: 回答は1文で")
    .assistant("わかりました")

for {
  client <- Client.create(llmServer, initial, maxToolCalls = 4)
  output <- client.send("東京の天気は?")
} yield output
```
