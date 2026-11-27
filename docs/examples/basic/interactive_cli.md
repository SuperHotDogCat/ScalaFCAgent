# 対話CLIエージェント

`Agent`にToolを6つ登録し, OpenAI互換サーバと会話するCLI。次の3つのモードを持つ。

- 引数を渡すと, その質問に1回答えてから対話モードに入る
- 引数なしで起動すると対話モード (REPL) になる
- `--stream`を付けると, モデルの出力を差分 (SSE) で逐次表示する

非ストリーミングのときは, `agent.history`の差分からTool呼び出し (`<tool>`) とToolの結果 (`<tool_result>`) を取り出して表示する。

使うAPI: [`Agent.create`](../../api/agent.md#agentcreate), [`Agent.send`](../../api/agent.md#send), [`Agent.sendStream`](../../api/agent.md#sendstream), [`Agent.history`](../../api/agent.md#history), [`Agent.reset`](../../api/agent.md#reset)

## 前提

- JDK 21
- OpenAI互換APIのサーバ。Ollamaの場合はモデルを`ollama pull`しておく

```bash
ollama pull gemma4:e2b
```

## プロジェクト構成

ライブラリはビルド済みのjarではなく, Gradleのcomposite buildでソースを取り込む。
`scalafctest`と`function-calling-made-by-FP`を同じ親ディレクトリに置く。

```
.
├── function-calling-made-by-FP/   # このライブラリ
└── scalafctest/
    ├── settings.gradle
    ├── build.gradle
    └── src/main/scala/example/
        ├── FcMain.scala           # このページ
        └── Tools.scala            # 型付きTool / Shell Tool のページ
```

=== "settings.gradle"

    ```groovy
    rootProject.name = 'scalafctest'

    // ../function-calling-made-by-FP の lib をそのままライブラリとして使う (composite build)
    includeBuild('../function-calling-made-by-FP') {
        dependencySubstitution {
            substitute module('org.scalafcagent:lib') using project(':lib')
        }
    }
    ```

=== "build.gradle"

    ```groovy
    plugins {
        id 'scala'
        id 'application'
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        // settings.gradle の dependencySubstitution で lib に置き換わる
        implementation 'org.scalafcagent:lib:0.0.0'

        implementation 'org.scala-lang:scala-library:2.13.18'

        // lib が implementation で持っている依存は, こちらでも明示する
        implementation 'org.typelevel:cats-effect_2.13:3.6.3'
        implementation 'io.circe:circe-core_2.13:0.14.15'
        implementation 'io.circe:circe-parser_2.13:0.14.15'
        implementation 'io.circe:circe-generic_2.13:0.14.15'
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    application {
        mainClass = 'example.FcMain'
    }

    // 引数なしで起動したときは対話モードになるので stdin をつなぐ
    tasks.named('run') {
        standardInput = System.in
    }
    ```

lib側を編集すると, `scalafctest`を次にビルドしたときに反映される。

## 実行

```bash
# 1回聞いてから対話モードに入る
./gradlew run --args="今の東京の時刻を教えて"

# 対話モード (exit / quit で終了, /reset で会話をリセット)
./gradlew run

# ストリーミング表示
./gradlew run --args="--stream 今の大阪の時刻は?"

# モデルやサーバを変える
LLM_MODEL=qwen3:1.7b ./gradlew run --args="札幌の天気を教えて"
LLM_BASE_URL=http://localhost:8000 LLM_MODEL=Qwen/Qwen3-8B ./gradlew run   # vLLM
```

| 環境変数 | デフォルト | 内容 |
| --- | --- | --- |
| `LLM_BASE_URL` | `http://localhost:11434` | OpenAI互換サーバ。`/v1/chat/completions`はライブラリが付ける |
| `LLM_MODEL` | `gemma4:e2b` | モデル名 |

## 出力例

非ストリーミングでは, `[user]`のあとにTool呼び出しとToolの結果が順に表示され, 最後に`[assistant]`の最終回答が出る。

```
you> 東京の天気と、12.5 + 7.25 の計算結果を教えて

[user]
東京の天気と、12.5 + 7.25 の計算結果を教えて

<tool>
{"function_name": "weather", "arguments": "{\"city\": \"Tokyo\"}"}
</tool>
<tool>
{"function_name": "add", "arguments": "{\"a\": 12.5, \"b\": 7.25}"}
</tool>

<tool_result>
Tokyo: 晴れ, 25度
</tool_result>

<tool_result>
19.75
</tool_result>

[assistant]
東京の天気は「晴れ」で25度です。計算結果は12.5 + 7.25 = 19.75です。
```

1回の出力に`<tool>`が複数あれば, 上から順にすべて実行してから次のリクエストを送る。
モデルが壊れたJSONを`arguments`に入れた場合は, `Schema`のdecodeに失敗して`Error: ...`がモデルに返り, モデルはそれを見て呼び直す。

## ソースコード

??? abstract "FcMain.scala"

    ```scala
    package example

    import cats.effect.ExitCode
    import cats.effect.IO
    import cats.effect.IOApp
    import cats.effect.Ref
    import cats.syntax.all._
    import org.scalafcagent.agent.Agent
    import org.scalafcagent.chat.Role
    import org.scalafcagent.llm.LLMServerConfig

    object FcMain extends IOApp {

      private def env(name: String, default: String): String =
        sys.env.getOrElse(name, default)

      private val baseUrl: String = env("LLM_BASE_URL", "http://localhost:11434")
      private val model: String = env("LLM_MODEL", "gemma4:e2b")

      private val llm: LLMServerConfig =
        LLMServerConfig(baseUrl = baseUrl, model = model)

      private def tools: List[org.scalafcagent.tool.Tool] =
        List(
          new CurrentTimeTool,
          new WeatherTool,
          new AddTool,
          new MulTool,
          new DivTool,
          new ShellTool
        )

      def run(args: List[String]): IO[ExitCode] = {
        val useStream = args.contains("--stream")
        val prompt = args.filterNot(_.startsWith("--")).mkString(" ").trim

        // Agent は Resource. use を抜けると HttpClient が閉じられる
        Agent.create(llm = llm, tools = tools: _*).use { agent =>
          for {
            cursor <- Ref.of[IO, Int](0) // 表示済みの履歴の位置
            _ <- banner(agent)
            _ <-
              if (prompt.nonEmpty)
                turn(agent, cursor, prompt, useStream) >> repl(agent, cursor, useStream)
              else
                repl(agent, cursor, useStream)
          } yield ExitCode.Success
        }
      }

      private def banner(agent: Agent): IO[Unit] =
        IO.println(s"model     : $model") >>
          IO.println(s"baseUrl   : $baseUrl") >>
          IO.println(s"tools     : ${tools.map(_.name).mkString(", ")}") >>
          IO.println("終了: exit  リセット: /reset")

      // 履歴のうち, まだ表示していない Tool 呼び出しと Tool 結果だけを表示する
      private def showTrace(
        agent: Agent,
        cursor: Ref[IO, Int],
        includeToolCall: Boolean
      ): IO[Unit] =
        for {
          history <- agent.history
          from <- cursor.get
          _ <- history.drop(from).traverse_ { message =>
            message.role match {
              case Role.Tool =>
                IO.println(s"\n${message.content.trim}")

              case Role.Assistant
                  if includeToolCall && message.content.contains("<tool>") =>
                IO.println(s"\n${message.content.trim}")

              case _ => IO.unit
            }
          }
          _ <- cursor.set(history.length)
        } yield ()

      // 1ターン
      private def turn(
        agent: Agent,
        cursor: Ref[IO, Int],
        input: String,
        useStream: Boolean
      ): IO[Unit] = {
        val run =
          if (useStream) {
            for {
              _ <- IO.println(s"\n[assistant]")
              _ <- agent.sendStream(input).evalMap { chunk =>
                  IO.print(chunk) >>
                    (if (chunk.contains("</tool>")) IO.println("") else IO.unit)
                }.compile.drain
            } yield ()
          } else {
            for {
              output <- agent.send(input)
              _ <- showTrace(agent, cursor, true)
              _ <- IO.println(s"\n[assistant]\n${output}")
            } yield ()
          }

        // LLMServer のエラーや Tool 呼び出し回数の超過でも REPL は止めない
        IO.println(s"\n[user]\n$input") >> run.handleErrorWith { error =>
          cursor.set(0) *> IO.println(s"\n[error] ${error.getMessage}")
        }
      }

      // 対話モード
      private def repl(
        agent: Agent,
        cursor: Ref[IO, Int],
        useStream: Boolean
      ): IO[Unit] =
        for {
          line <- IO.blocking(Option(scala.io.StdIn.readLine()))
          _ <- line.map(_.trim) match {
            case None | Some("exit") | Some("quit") =>
              IO.println("bye")

            case Some("") =>
              repl(agent, cursor, useStream)

            case Some("/reset") =>
              agent.reset *>
                cursor.set(0) *>
                IO.println("(会話をリセットしました)") *>
                repl(agent, cursor, useStream)

            case Some(input) =>
              turn(agent, cursor, input, useStream) *>
                repl(agent, cursor, useStream)
          }
        } yield ()
    }
    ```

Toolの定義 (`CurrentTimeTool`など) は[型付きToolを作る](../tools/typed_tools.md)と[Shellを実行するTool](../tools/shell_tool.md)にある。

## 要点

### Agentのライフサイクルは`Resource`で管理する

`Agent.create`は`Resource[IO, Agent]`を返す。`use`の中でだけAgentを使い, 抜けると (例外やキャンセルで抜けた場合も) LLMServerへの`HttpClient`が閉じられる。

```scala
Agent.create(llm, tools: _*).use { agent =>
  agent.send("東京の天気を教えて").flatMap(IO.println)
}
```

### 副作用は`IO`として`for`で順に並べる

LLMへのリクエスト, Toolの実行, 会話履歴の更新はすべて`IO`で表される。`for`で並べると実行順が決まり, 最後の`IO`が`IOApp`で実行される。

```scala
for {
  _        <- banner(agent)
  response <- agent.send(prompt)
  _        <- IO.println(response)
} yield ExitCode.Success
```

### ストリーミングは`fs2.Stream`を`compile.drain`する

`sendStream`は`Stream[IO, String]`を返す。`evalMap`で差分ごとに表示し, `compile.drain`で`IO[Unit]`にする。

```scala
agent.sendStream(input)
  .evalMap(chunk => IO.print(chunk))
  .compile
  .drain
```

ストリーミングではTool呼び出し (`<tool>...</tool>`) も本文の差分としてそのまま流れる。Toolの結果はモデルにだけ返し, 差分には流れない。

### Tool呼び出しの往復は`history`で見る

`send`の戻り値はTool解決後の最終出力だけ。途中の往復は`agent.history`に`Role.Assistant` (`<tool>`を含む) と`Role.Tool` (`<tool_result>`) として残る。
この例では`Ref[IO, Int]`に表示済みの位置を持ち, その後ろだけを表示している。

### エラーは`handleErrorWith`で拾う

`send`は次の場合に失敗した`IO`を返す。

- LLMServerが2xx以外を返した
- Tool呼び出しが`AgentConfig.maxToolCalls` (既定8) 回を超えて続いた

REPLを止めないよう, `handleErrorWith`でメッセージを表示して次の入力へ進む。
