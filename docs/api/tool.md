# org.scalafcagent.tool

Toolの定義と実行。

```scala
import org.scalafcagent.tool.{Schema, Tool, ToolCall, ToolEngine, ToolError, ToolResult, TypedTool}
```

| 名前 | 種類 | 内容 |
| --- | --- | --- |
| [`TypedTool`](#typedtool) | trait | 型付きTool。アプリケーションはこれを継承する |
| [`Schema`](#schema) | trait | 引数の型とJSON文字列の相互変換 |
| [`ToolResult`](#toolresult) | sealed trait | Toolの実行結果 |
| [`Tool`](#tool) | trait | 型消去されたTool |
| [`ToolCall`](#toolcall) | case class | モデル出力から取り出したTool呼び出し |
| [`ToolEngine`](#toolengine) | class | 名前からToolを引いて実行する |
| [`ToolError`](#toolerror) | case class | 引数のdecodeエラー |

---

## `TypedTool`

```scala
trait TypedTool[A] extends Tool {
  def schema: Schema[A]
  def run(args: A): IO[ToolResult]

  final override def execute(input: String): IO[ToolResult]
}
```

**Bases:** [`Tool`](#tool)

引数を型`A`で受け取るTool。LLMが出したJSON文字列は`schema`で`A`に変換されてから`run`に渡る。
アプリケーションでToolを作るときはこれを継承し, `name` / `description` / `argumentJSONSchema` / `schema` / `run`を実装する。

**Type Parameters:**

- **A** — 引数のcase class

**実装するメンバー:**

- **name** (`String`) — Tool名。モデルは`function_name`にこれを書く。Agent内で一意にする
- **description** (`String`) — Toolの説明。system promptに載る
- **argumentJSONSchema** (`String`) — 引数のJSON Schema。system promptの`parameters`に載る。JSONとしてparseできない場合は文字列のまま載る
- **schema** ([`Schema[A]`](#schema)) — 引数の変換
- **run** (`A => IO[ToolResult]`) — 本体

### `run`

```scala
def run(args: A): IO[ToolResult]
```

Toolの本体。

**Parameters:**

- **args** (`A`) — decode済みの引数

**Returns:**

- `IO[ToolResult]` — `ToolResult.Success(value)`なら`value`が, `ToolResult.Error(message)`なら`Error: message`がモデルに返る

`IO`自体が失敗すると, モデルには返らず`Agent.send`全体の失敗になる。モデルにやり直させたい失敗は`ToolResult.Error`で返す。

### `execute`

```scala
final override def execute(input: String): IO[ToolResult]
```

`schema.decode(input)`に成功すれば`run`を呼ぶ。失敗すれば`ToolResult.Error(<decodeのエラーメッセージ>)`を返す。`final`なのでオーバーライドできない。

**Parameters:**

- **input** (`String`) — モデルが出した`arguments`のJSON文字列

**Returns:**

- `IO[ToolResult]`

**Example:**

```scala
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.scalafcagent.tool.{Schema, ToolResult, TypedTool}

case class AddArguments(a: Double, b: Double)

object AddSchema extends Schema[AddArguments] {
  implicit val decoder: Decoder[AddArguments] = deriveDecoder
  implicit val encoder: Encoder[AddArguments] = deriveEncoder
}

class AddTool extends TypedTool[AddArguments] {
  val name = "add"
  val description = "2つの数値を足し算して結果を返す"
  val argumentJSONSchema =
    """{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}"""
  val schema = AddSchema

  def run(args: AddArguments): IO[ToolResult] =
    IO.pure(ToolResult.Success(s"${args.a + args.b}"))
}

new AddTool().execute("""{"a": 1, "b": 2}""")   // IO(Success("3.0"))
new AddTool().execute("""{"a": "x"}""")         // IO(Error("..."))  decodeの失敗
```

ほかの例は[型付きToolを作る](../examples/tools/typed_tools.md)を参照。

---

## `Schema`

```scala
trait Schema[A] {
  implicit val decoder: Decoder[A]
  implicit val encoder: Encoder[A]

  def encode(input: A): String
  def decode(input: String): Either[ToolError, A]
}
```

引数の型`A`とJSON文字列の相互変換。circeの`Decoder` / `Encoder`を実装すると`encode` / `decode`が使える。

**Type Parameters:**

- **A** — 引数の型

**実装するメンバー:**

- **decoder** (`io.circe.Decoder[A]`) — 通常は`deriveDecoder[A]`
- **encoder** (`io.circe.Encoder[A]`) — 通常は`deriveEncoder[A]`

### `encode`

```scala
def encode(input: A): String
```

**Parameters:**

- **input** (`A`) — 変換する値

**Returns:**

- `String` — 空白なしのJSON文字列

### `decode`

```scala
def decode(input: String): Either[ToolError, A]
```

**Parameters:**

- **input** (`String`) — JSON文字列

**Returns:**

- `Either[ToolError, A]` — 成功すれば`Right(A)`。JSONとして不正, またはフィールドの型が合わなければ`Left(ToolError(<circeのエラーメッセージ>))`

**Example:**

```scala
AddSchema.encode(AddArguments(1, 2))       // """{"a":1.0,"b":2.0}"""
AddSchema.decode("""{"a": 1, "b": 2}""")   // Right(AddArguments(1.0, 2.0))
AddSchema.decode("""{"a": 1}""")           // Left(ToolError("..."))
```

---

## `ToolResult`

```scala
sealed trait ToolResult

object ToolResult {
  case class Success(value: String) extends ToolResult
  case class Error(message: String) extends ToolResult
}
```

Toolの実行結果。どちらもモデルに返され, 会話に`Role.Tool`のメッセージとして追加される。

| 値 | モデルに返る内容 (デフォルトの形式) |
| --- | --- |
| `ToolResult.Success(value)` | `<tool_result>\nvalue\n</tool_result>` |
| `ToolResult.Error(message)` | `<tool_result>\nError: message\n</tool_result>` |

**Example:**

```scala
def run(args: WeatherArguments): IO[ToolResult] =
  IO.pure(
    table.get(args.city) match {
      case Some(weather) => ToolResult.Success(weather)
      case None          => ToolResult.Error(s"天気データがありません: ${args.city}")
    }
  )
```

---

## `Tool`

```scala
trait Tool {
  def name: String
  def description: String
  def argumentJSONSchema: String
  def execute(input: String): IO[ToolResult]
}
```

[`ToolEngine`](#toolengine)から扱うための, 型消去されたTool。通常は[`TypedTool`](#typedtool)を継承する。
引数をJSON文字列のまま扱いたい場合 (引数が不定形など) は`Tool`を直接実装してもよい。

**Members:**

- **name** (`String`) — Tool名
- **description** (`String`) — Toolの説明
- **argumentJSONSchema** (`String`) — 引数のJSON Schema
- **execute** (`String => IO[ToolResult]`) — `arguments`のJSON文字列を受け取って実行する

**Example:**

```scala
// 引数を取らないTool
object PingTool extends Tool {
  val name = "ping"
  val description = "疎通確認"
  val argumentJSONSchema = """{"type":"object","properties":{}}"""
  def execute(input: String): IO[ToolResult] = IO.pure(ToolResult.Success("pong"))
}
```

---

## `ToolCall`

```scala
case class ToolCall(
  functionName: String,
  arguments: String
)
```

モデル出力から取り出したTool呼び出し。[`ChatFormat.decodeToolCall`](./chat.md#chatformat)が作る。

**Parameters:**

- **functionName** (`String`) — 呼び出すTool名
- **arguments** (`String`) — 引数のJSON文字列。デフォルトの形式では, モデルが`arguments`をJSON文字列で出してもJSONオブジェクトで出しても, ここには文字列が入る

---

## `ToolEngine`

```scala
class ToolEngine(val tools: Tool*) {
  def call(toolCall: ToolCall): IO[ToolResult]
}
```

登録されたToolを名前で引いて実行する。同じ名前のToolが複数あると, 後のものが使われる。

**Parameters:**

- **tools** (`Tool*`) — 登録するTool

### `call`

```scala
def call(toolCall: ToolCall): IO[ToolResult]
```

**Parameters:**

- **toolCall** ([`ToolCall`](#toolcall)) — 実行するTool呼び出し

**Returns:**

- `IO[ToolResult]` — `tool.execute(toolCall.arguments)`の結果。未登録の名前なら`ToolResult.Error("Unknown tool: <name>")`

**Example:**

```scala
val engine = new ToolEngine(new AddTool)

engine.call(ToolCall("add", """{"a": 1, "b": 2}"""))  // IO(Success("3.0"))
engine.call(ToolCall("sub", "{}"))                    // IO(Error("Unknown tool: sub"))
```

---

## `ToolError`

```scala
case class ToolError(message: String)
```

[`Schema.decode`](#decode)が返すエラー。`TypedTool.execute`はこれを`ToolResult.Error(message)`に変換する。

**Parameters:**

- **message** (`String`) — circeのエラーメッセージ
