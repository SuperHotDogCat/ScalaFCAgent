# モデルごとのTool呼び出し形式 (ChatFormat)

デフォルトでは, モデルに次のXML形式でToolを呼ばせる。

```
<tool>
{"function_name": "search", "arguments": "{\"query\": \"Scala\", \"limit\": 10}"}
</tool>
```

ファインチューニングで独自のTool呼び出し形式を覚えているモデルは, その形式のほうが安定することがある。
`ChatFormat`を組み立てて`AgentConfig.format`に渡すと, 次の3点を差し替えられる。

| フィールド | 役割 |
| --- | --- |
| `toolCallPattern` | モデル出力からTool呼び出しを取り出す正規表現。`group(1)`が`decodeToolCall`に渡る |
| `decodeToolCall` | 取り出した文字列を`ToolCall(functionName, arguments)`に変換する |
| `renderToolResult` | Toolの結果を会話に載せる形式 |
| `renderTools` | 登録したToolをsystem promptに載せる文言 |

使うAPI: [`ChatFormat`](../../api/chat.md#chatformat), [`AgentConfig`](../../api/agent.md#agentconfig), [`ToolCall`](../../api/tool.md#toolcall)

## 例1: Hermes形式 (Qwen2.5 / Qwen3 / QwQ / Nous Hermes)

```
<tool_call>
{"name": "search", "arguments": {"query": "Scala", "limit": 10}}
</tool_call>
```

```scala
import io.circe.parser.parse
import org.scalafcagent.agent.{Agent, AgentConfig}
import org.scalafcagent.chat.ChatFormat
import org.scalafcagent.tool.ToolCall

val hermesFormat: ChatFormat =
  ChatFormat(
    toolCallPattern = """(?s)<tool_call>\s*(\{.*?\})\s*</tool_call>""".r,
    decodeToolCall = input =>
      parse(input).toOption.flatMap { json =>
        val cursor = json.hcursor
        val arguments = cursor.downField("arguments")

        for {
          name <- cursor.get[String]("name").toOption
          // arguments は JSON オブジェクトでも文字列でも受け付ける
          args <- arguments.as[String].toOption
            .orElse(arguments.focus.map(_.noSpaces))
        } yield ToolCall(functionName = name, arguments = args)
      },
    renderToolResult = value => s"<tool_response>\n$value\n</tool_response>"
  )

Agent.create(llm, AgentConfig(format = hermesFormat), new SearchTool)
```

`renderTools`を指定していないので, system promptのTool説明はデフォルト (`<tool>`形式の説明) のまま。
モデルに出力形式も合わせて指示したい場合は`renderTools`も差し替える。

```scala
import io.circe.Json
import io.circe.syntax._

hermesFormat.copy(
  renderTools = tools =>
    if (tools.isEmpty) ""
    else {
      val definitions = tools.map { tool =>
        Json.obj(
          "name" -> tool.name.asJson,
          "description" -> tool.description.asJson,
          "parameters" -> parse(tool.argumentJSONSchema).getOrElse(Json.obj())
        ).noSpaces
      }

      "# Tools\n\n<tools>\n" + definitions.mkString("\n") + "\n</tools>\n\n" +
        "To call a function, output:\n" +
        "<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>"
    }
)
```

`renderTools`が空文字列を返すと, system promptにTool説明は追加されない。

## 例2: Qwen3-Coder (XMLの`<function=...>`)

```
<tool_call>
<function=search>
<parameter=query>Scala</parameter>
<parameter=limit>10</parameter>
</function>
</tool_call>
```

パラメータをJSONオブジェクトに組み立て直して`arguments`にする。

```scala
import scala.util.matching.Regex

val functionNamePattern: Regex = """<function=([^>\s]+)>""".r
val parameterPattern: Regex = """<parameter=([^>\s]+)>(.*?)</parameter>""".r

def quote(value: String): String = "\"" + value + "\""

// 数値・真偽値・null はそのまま, それ以外は文字列にする
def jsonValue(raw: String): String = {
  val value = raw.trim
  if (value.matches("""-?\d+""") || value.matches("""-?\d*\.\d+""") ||
      value == "true" || value == "false" || value == "null") value
  else quote(value)
}

val qwen3CoderFormat: ChatFormat =
  ChatFormat(
    toolCallPattern = """(?s)<tool_call>\s*(<function=.*?</function>)\s*</tool_call>""".r,
    decodeToolCall = input =>
      functionNamePattern.findFirstMatchIn(input).map { name =>
        val arguments = parameterPattern
          .findAllMatchIn(input)
          .map(p => s"${quote(p.group(1))}:${jsonValue(p.group(2))}")
          .mkString("{", ",", "}")

        ToolCall(functionName = name.group(1), arguments = arguments)
      },
    renderToolResult = value => s"<tool_response>\n$value\n</tool_response>"
  )
```

## そのほかの形式

[`lib/src/test/scala/org/example/ChatFormat.scala`](https://github.com/SuperHotDogCat/ScalaFCAgent/blob/main/lib/src/test/scala/org/example/ChatFormat.scala)に, 次の形式の`ChatFormat`とテストがある。

| モデル | 呼び出し | 結果 |
| --- | --- | --- |
| Hermes系 | `<tool_call>{"name": ..., "arguments": {...}}</tool_call>` | `<tool_response>...</tool_response>` |
| Qwen3-Coder | `<tool_call><function=...><parameter=...>...</parameter></function></tool_call>` | `<tool_response>...</tool_response>` |
| DeepSeek V3.1 / V3.2 | `<｜tool▁call▁begin｜>name<｜tool▁sep｜>{...}<｜tool▁call▁end｜>` | `<｜tool▁output▁begin｜>...<｜tool▁output▁end｜>` |
| Gemma 3 | `tool_code`のコードブロックに`search(query="Scala", limit=10)` | `tool_output`のコードブロック |

## 形式をテストする

LLMを立てなくても, `Chat`に出力文字列を渡せば形式を確認できる。

```scala
import cats.effect.unsafe.implicits.global
import org.scalafcagent.chat.Chat
import org.scalafcagent.tool.ToolEngine

val chat = new Chat(toolEngine = new ToolEngine(new SearchTool), format = hermesFormat)

val output =
  """<tool_call>
    |{"name": "search", "arguments": {"query": "Scala", "limit": 10}}
    |</tool_call>""".stripMargin

chat.toolCalls(output)
// Vector(ToolCall("search", "{\"query\":\"Scala\",\"limit\":10}"))

chat.runToolCalls(output).unsafeRunSync().messages
// Vector(Message(Tool, "<tool_response>\nSearching for 'Scala' (limit=10)\n</tool_response>"))
```

## 注意

- `decodeToolCall`が`None`を返した呼び出しは無視される。出力にTool呼び出しが1つも残らなければ, その出力が最終回答として`send`から返る
- `toolCallPattern`は非貪欲 (`.*?`) にしておく。1つの出力に複数の呼び出しがあっても, それぞれを取り出せる
- `Role.Tool`のメッセージはOpenAI互換APIへは`user`ロールで送られる ([ロールの対応](../../api/llm.md#roleとopenai-roleの対応))
