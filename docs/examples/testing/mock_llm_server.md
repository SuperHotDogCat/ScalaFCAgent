# モックLLMServerでテストする

`LLMServer.apply`で「決まった出力を順番に返すLLMServer」を作り, `Agent.from`に渡す。
LLMを立てずに, Tool呼び出しの往復・system prompt・履歴を決定的にテストできる。

使うAPI: [`LLMServer.apply`](../../api/llm.md#llmserverapply), [`Agent.from`](../../api/agent.md#agentfrom)

## モックLLMServer

```scala
import cats.effect.IO
import cats.effect.Ref
import org.scalafcagent.llm.LLMServer

// 用意した出力を順番に返す. 足りなくなったら失敗する
def mockLLMServer(responses: List[String]): IO[LLMServer] =
  Ref.of[IO, List[String]](responses).map { queue =>
    LLMServer { _ =>
      queue
        .modify {
          case head :: tail => (tail, IO.pure(head))
          case Nil          => (Nil, IO.raiseError[String](new RuntimeException("no more responses")))
        }
        .flatten
    }
  }
```

`LLMServer.apply`に渡す関数は, 送られる`messages`全体を受け取る。送られた内容を検証したい場合は, 引数の`messages`を`Ref`に記録すればよい。

## Toolの往復をテストする

1回目の出力でToolを呼ばせ, 2回目で最終回答を返させる。

```scala
import cats.effect.unsafe.implicits.global
import org.scalafcagent.agent.{Agent, AgentConfig}
import org.scalafcagent.chat.Role
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WeatherAgentSpec extends AnyFunSuite with Matchers {

  private val toolCall: String =
    """<tool>
      |{"function_name": "weather", "arguments": "{\"city\": \"Tokyo\"}"}
      |</tool>""".stripMargin

  test("weather Tool を呼んでから最終回答を返す") {
    val program =
      for {
        server <- mockLLMServer(List(toolCall, "東京は晴れです"))
        result <- Agent.from(server, AgentConfig(), new WeatherTool).use { agent =>
          for {
            output  <- agent.send("東京の天気は?")
            history <- agent.history
          } yield (output, history)
        }
      } yield result

    val (output, history) = program.unsafeRunSync()

    output shouldBe "東京は晴れです"

    history.map(_.role) shouldBe Vector(
      Role.System,    // Tool の使い方
      Role.User,      // 東京の天気は?
      Role.Assistant, // <tool>...</tool>
      Role.Tool,      // <tool_result>Tokyo: 晴れ, 25度</tool_result>
      Role.Assistant  // 東京は晴れです
    )

    history(3).content shouldBe "<tool_result>\nTokyo: 晴れ, 25度\n</tool_result>"
  }

  test("Tool 呼び出しが続くと maxToolCalls で止まる") {
    val program =
      for {
        server <- mockLLMServer(List.fill(10)(toolCall))
        output <- Agent
          .from(server, AgentConfig(maxToolCalls = 3), new WeatherTool)
          .use(_.send("東京の天気は?"))
          .attempt
      } yield output

    program.unsafeRunSync().left.map(_.getMessage) shouldBe
      Left("Tool call limit exceeded: 3")
  }
}
```

## 要点

- `Agent.from`は`LLMServer`を外から受け取る。本番では`Agent.create` (内部で`OpenAILLMServer`を作る) を使う
- `LLMServer`の`stream`はデフォルトで`complete`の結果を1つの差分として返すので, モックでも`sendStream`をテストできる
- Toolを登録すると, 履歴の先頭に必ず`Role.System`のメッセージ (Toolの使い方) が入る
- HTTPサーバも`HttpServer.from(mock, ...)`で同様にテストできる ([`server/src/test/scala/org/HttpServerSpec.scala`](https://github.com/SuperHotDogCat/ScalaFCAgent/blob/main/server/src/test/scala/org/HttpServerSpec.scala))
