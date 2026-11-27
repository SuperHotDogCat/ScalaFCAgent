# 型付きToolを作る

`TypedTool[A]`を継承してToolを定義する例。引数はcase classで受け取り, LLMが出したJSON文字列からの変換は`Schema[A]`が行う。

この例では次の5つのToolを作る ([Shellを実行するTool](./shell_tool.md)を合わせて6つ)。

| name | 引数 | 内容 |
| --- | --- | --- |
| `current_time` | `timezone: String` | 指定したIANAタイムゾーンの現在時刻 (ISO 8601) |
| `weather` | `city: String` | 東京 / 大阪 / 札幌 / 福岡の天気 (固定データ) |
| `add` | `a: Double`, `b: Double` | 足し算 |
| `mul` | `a: Double`, `b: Double` | 掛け算 |
| `div` | `a: Double`, `b: Double` | 割り算 |

使うAPI: [`TypedTool`](../../api/tool.md#typedtool), [`Schema`](../../api/tool.md#schema), [`ToolResult`](../../api/tool.md#toolresult)

## Toolの組み立て方

1つのToolは次の3つで構成する。

1. **引数のcase class** — `run`が受け取る型
2. **`Schema[A]`** — circeの`Decoder` / `Encoder`。JSON文字列とcase classを相互変換する
3. **`TypedTool[A]`** — `name` / `description` / `argumentJSONSchema`と`run`を持つ

```scala
// 1. 引数
case class WeatherArguments(city: String)

// 2. Schema
object WeatherSchema extends Schema[WeatherArguments] {
  implicit val decoder: Decoder[WeatherArguments] = deriveDecoder[WeatherArguments]
  implicit val encoder: Encoder[WeatherArguments] = deriveEncoder[WeatherArguments]
}

// 3. Tool
class WeatherTool extends TypedTool[WeatherArguments] {
  val name: String = "weather"
  val description: String = "指定した都市の現在の天気と気温を返す (対応都市: 東京, 大阪, 札幌, 福岡)"
  val argumentJSONSchema: String =
    """{"type":"object","properties":{"city":{"type":"string","description":"City name, e.g. Tokyo"}},"required":["city"]}"""
  val schema: Schema[WeatherArguments] = WeatherSchema

  def run(args: WeatherArguments): IO[ToolResult] = ...
}
```

`name` / `description` / `argumentJSONSchema`は`Agent.create`のときにsystem promptに載り, モデルはそれを見てToolを呼ぶ。
`argumentJSONSchema`は今のところ手で書く。case classのフィールド名と`properties`のキーを揃えること。

## LLMからの呼び出しと実行の流れ

モデルが次のように出力すると,

```
<tool>
{"function_name": "add", "arguments": "{\"a\": 12.5, \"b\": 7.25}"}
</tool>
```

ライブラリは次の順で処理する。

1. `function_name`から登録済みのTool (`add`) を探す。見つからなければ`Error: Unknown tool: add`を返す
2. `arguments`を`AddSchema.decode`で`AddArguments(12.5, 7.25)`に変換する。失敗したらdecodeのエラーメッセージを`Error: ...`として返す
3. `run(AddArguments(12.5, 7.25))`を実行する
4. 結果を`<tool_result>`で包み, 会話に追加してモデルへ送る

```
<tool_result>
19.75
</tool_result>
```

`run`の中では型付きの値だけを扱えばよく, JSONの検証は書かなくてよい。

## ソースコード

??? abstract "Tools.scala (current_time / weather / add / mul / div)"

    ```scala
    package example

    import java.time.ZoneId
    import java.time.ZonedDateTime
    import java.time.format.DateTimeFormatter

    import cats.effect.IO
    import io.circe.Decoder
    import io.circe.Encoder
    import io.circe.generic.semiauto.deriveDecoder
    import io.circe.generic.semiauto.deriveEncoder
    import org.scalafcagent.tool.Schema
    import org.scalafcagent.tool.ToolResult
    import org.scalafcagent.tool.TypedTool

    // --------------------
    // 1. 現在時刻を返すTool
    // --------------------

    case class CurrentTimeArguments(timezone: String)

    object CurrentTimeSchema extends Schema[CurrentTimeArguments] {
      implicit val decoder: Decoder[CurrentTimeArguments] = deriveDecoder[CurrentTimeArguments]
      implicit val encoder: Encoder[CurrentTimeArguments] = deriveEncoder[CurrentTimeArguments]
    }

    class CurrentTimeTool extends TypedTool[CurrentTimeArguments] {

      val name: String = "current_time"

      val description: String =
        "指定したIANAタイムゾーンの現在時刻をISO 8601形式で返す"

      val argumentJSONSchema: String =
        """{"type":"object","properties":{"timezone":{"type":"string","description":"IANA timezone, e.g. Asia/Tokyo"}},"required":["timezone"]}"""

      val schema: Schema[CurrentTimeArguments] = CurrentTimeSchema

      def run(args: CurrentTimeArguments): IO[ToolResult] =
        IO.blocking(ZoneId.of(args.timezone)).attempt.map {
          case Right(zone) =>
            ToolResult.Success(
              ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            )

          case Left(_) =>
            ToolResult.Error(s"Unknown timezone: ${args.timezone}")
        }
    }

    // --------------------
    // 2. 天気を返すTool (ダミーデータ)
    // --------------------

    case class WeatherArguments(city: String)

    object WeatherSchema extends Schema[WeatherArguments] {
      implicit val decoder: Decoder[WeatherArguments] = deriveDecoder[WeatherArguments]
      implicit val encoder: Encoder[WeatherArguments] = deriveEncoder[WeatherArguments]
    }

    class WeatherTool extends TypedTool[WeatherArguments] {

      val name: String = "weather"

      val description: String =
        "指定した都市の現在の天気と気温を返す (対応都市: 東京, 大阪, 札幌, 福岡)"

      val argumentJSONSchema: String =
        """{"type":"object","properties":{"city":{"type":"string","description":"City name, e.g. Tokyo"}},"required":["city"]}"""

      val schema: Schema[WeatherArguments] = WeatherSchema

      // 本物のAPIを叩く代わりに固定のデータを返す
      private val table: Map[String, (String, Int)] =
        Map(
          "東京" -> ("晴れ", 25),
          "tokyo" -> ("晴れ", 25),
          "大阪" -> ("曇り", 23),
          "osaka" -> ("曇り", 23),
          "札幌" -> ("雪", -2),
          "sapporo" -> ("雪", -2),
          "福岡" -> ("雨", 20),
          "fukuoka" -> ("雨", 20)
        )

      def run(args: WeatherArguments): IO[ToolResult] =
        IO.pure(
          table.get(args.city.trim.toLowerCase) match {
            case Some((condition, temperature)) =>
              ToolResult.Success(s"${args.city}: $condition, ${temperature}度")

            case None =>
              ToolResult.Error(s"天気データがありません: ${args.city}")
          }
        )
    }

    // --------------------
    // 3-5. 四則演算のTool
    // --------------------

    case class AddArguments(a: Double, b: Double)

    object AddSchema extends Schema[AddArguments] {
      implicit val decoder: Decoder[AddArguments] = deriveDecoder[AddArguments]
      implicit val encoder: Encoder[AddArguments] = deriveEncoder[AddArguments]
    }

    class AddTool extends TypedTool[AddArguments] {

      val name: String = "add"

      val description: String = "2つの数値を足し算して結果を返す"

      val argumentJSONSchema: String =
        """{"type":"object","properties":{"a":{"type":"number","description":"left operand"},"b":{"type":"number","description":"right operand"}},"required":["a","b"]}"""

      val schema: Schema[AddArguments] = AddSchema

      def run(args: AddArguments): IO[ToolResult] =
        IO.pure(ToolResult.Success(s"${args.a + args.b}"))
    }

    // MulTool (name = "mul", a * b) と DivTool (name = "div", a / b) も同じ形
    ```

## 要点

### 失敗は`ToolResult.Error`で返す

`run`で例外を投げる代わりに`ToolResult.Error`を返すと, `Error: <message>`としてモデルに渡り, モデルが引数を直して呼び直せる。
`CurrentTimeTool`は`IO.attempt`で`ZoneId.of`の例外を受け取り, `Unknown timezone`に変換している。

!!! warning "例外を`run`から投げた場合"
    `run`が返す`IO`が失敗すると, Toolの結果ではなく`Agent.send`全体の失敗になる。モデルにやり直させたい失敗は`ToolResult.Error`で返すこと。

### ブロッキングする処理は`IO.blocking`で包む

ファイルやネットワーク, 外部プロセスなど, スレッドをブロックする処理は`IO.blocking`で包む。計算だけで済むもの (`add`など) は`IO.pure`でよい。

### 引数の型はJSON Schemaと揃える

`AddArguments(a: Double, b: Double)`に対し`argumentJSONSchema`は`"type":"number"`にしている。モデルが`"a": "12.5"`のように文字列で送ると`Decoder[Double]`が失敗し, `Error: ...`がモデルに返る。

### `div`のゼロ除算

`DivTool`は`a / b`をそのまま返すので, `b = 0`のときは`Infinity`や`NaN`が結果になる。モデルに失敗として扱わせたい場合は`run`の中で`b == 0`を判定して`ToolResult.Error`を返す。

```scala
def run(args: DivArguments): IO[ToolResult] =
  IO.pure(
    if (args.b == 0) ToolResult.Error("division by zero")
    else ToolResult.Success(s"${args.a / args.b}")
  )
```
