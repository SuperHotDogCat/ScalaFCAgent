# Shellを実行するTool

LLMが生成した1行のコマンドを`sh -c`で実行し, 標準出力と標準エラーをまとめて返すTool。
外部プロセスを扱うTool (タイムアウト, 出力の打ち切り, 一時ファイルの後始末) の書き方の例になっている。

使うAPI: [`TypedTool`](../../api/tool.md#typedtool), [`ToolResult`](../../api/tool.md#toolresult)

!!! danger "本物の環境では登録しないこと"
    `shell`はLLMが生成したコマンドをそのまま実行する。実行ユーザーの権限で何でも動くので, 隔離されていない環境では`Agent`に登録しないこと
    ([対話CLIエージェント](../basic/interactive_cli.md)の`tools`から`new ShellTool`を外す)。

## 仕様

| 項目 | 内容 |
| --- | --- |
| name | `shell` |
| 引数 | `s: String` (1行のコマンド) |
| 作業ディレクトリ | 起動時のカレントディレクトリ (`user.dir`) |
| タイムアウト | 10秒 (`new ShellTool(30.seconds)`で変更) |
| 出力 | 標準出力と標準エラーをまとめ, 先頭4000文字まで |
| 終了コードが0 | `ToolResult.Success(出力)` |
| 終了コードが0以外 | `ToolResult.Error("exit code N\n出力")` |
| タイムアウト | プロセスを強制終了し, `ToolResult.Error("timeout after Ns\n出力")` |

失敗も`ToolResult.Error`としてモデルに返すので, モデルはエラー出力を見てコマンドを直せる。

## 実行例

出力の内容はモデルと実行環境によって変わる。

```bash
./gradlew run --args="カレントディレクトリのファイル一覧を出して"
```

```
[user]
カレントディレクトリのファイル一覧を出して

<tool>
{"function_name": "shell", "arguments": "{\"s\": \"ls\"}"}
</tool>

<tool_result>
README.md
build.gradle
gradle
gradlew
gradlew.bat
settings.gradle
src
</tool_result>

[assistant]
カレントディレクトリには README.md, build.gradle, ... があります。
```

## ソースコード

??? abstract "ShellTool (Tools.scala より)"

    ```scala
    package example

    import java.io.File
    import java.io.FileInputStream
    import java.nio.charset.StandardCharsets.UTF_8
    import java.nio.file.Files
    import java.nio.file.Path
    import java.util.concurrent.TimeUnit

    import scala.concurrent.duration.DurationInt
    import scala.concurrent.duration.FiniteDuration

    import cats.effect.IO
    import io.circe.Decoder
    import io.circe.Encoder
    import io.circe.generic.semiauto.deriveDecoder
    import io.circe.generic.semiauto.deriveEncoder
    import org.scalafcagent.tool.Schema
    import org.scalafcagent.tool.ToolResult
    import org.scalafcagent.tool.TypedTool

    case class ShellArguments(s: String)

    object ShellSchema extends Schema[ShellArguments] {
      implicit val decoder: Decoder[ShellArguments] = deriveDecoder[ShellArguments]
      implicit val encoder: Encoder[ShellArguments] = deriveEncoder[ShellArguments]
    }

    class ShellTool(timeout: FiniteDuration = 10.seconds) extends TypedTool[ShellArguments] {

      val name: String = "shell"

      val description: String =
        "1行のShellコマンドを sh -c で実行し, 標準出力と標準エラーをまとめて返す"

      val argumentJSONSchema: String =
        """{"type":"object","properties":{"s":{"type":"string","description":"one line command by shell"}},"required":["s"]}"""

      val schema: Schema[ShellArguments] = ShellSchema

      // 出力が長すぎるとコンテキストを食うので先頭だけ返す
      private val maxOutput: Int = 4000

      def run(args: ShellArguments): IO[ToolResult] =
        IO.blocking(Files.createTempFile("scalafc-shell-", ".log")).flatMap { outputFile =>
          val action =
            for {
              // パイプが詰まらないよう, 出力はファイルへ逃がす
              process <- IO.blocking {
                new ProcessBuilder("sh", "-c", args.s)
                  .directory(new File(System.getProperty("user.dir")))
                  .redirectErrorStream(true)
                  .redirectOutput(outputFile.toFile)
                  .start()
              }
              finished <- IO.blocking(
                process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
              )
              _ <-
                if (finished) IO.unit
                else IO.blocking(process.destroyForcibly()).void *> IO.blocking(process.waitFor()).void
              code <- IO.blocking(process.exitValue())
              output <- IO.blocking(readLimited(outputFile))
            } yield toResult(finished, code, output)

          // 成功しても失敗しても一時ファイルを消す
          action.guarantee(IO.blocking(Files.deleteIfExists(outputFile)).void)
        }

      private def readLimited(path: Path): String = {
        val in = new FileInputStream(path.toFile)
        try {
          val bytes = in.readNBytes(maxOutput + 1)
          val text = new String(bytes, UTF_8)

          if (bytes.length > maxOutput) text.take(maxOutput) + "\n...(truncated)"
          else text
        } finally {
          in.close()
        }
      }

      private def toResult(finished: Boolean, code: Int, output: String): ToolResult = {
        val body =
          if (output.trim.isEmpty) "(no output)"
          else output.trim

        if (!finished) {
          ToolResult.Error(s"timeout after ${timeout.toSeconds}s\n$body")
        } else if (code != 0) {
          // 失敗もモデルに返して, やり直させる
          ToolResult.Error(s"exit code $code\n$body")
        } else {
          ToolResult.Success(body)
        }
      }
    }
    ```

## 要点

### コンストラクタ引数で設定を持たせる

`TypedTool`は普通のクラスなので, コンストラクタ引数で設定を渡せる。

```scala
Agent.create(llm, new ShellTool(timeout = 30.seconds))
```

### 出力はパイプではなく一時ファイルへ

`Process.getInputStream`を読まずに`waitFor`すると, 出力が大きいときにパイプのバッファが埋まってプロセスが止まる。
ここでは`redirectOutput`で一時ファイルに書かせ, 終了後に先頭`maxOutput + 1`バイトだけ読む。

### 後始末は`guarantee`で

`action.guarantee(...)`は`action`が成功・失敗・キャンセルのどれで終わっても一時ファイルを削除する。

### 出力の長さを制限する

Toolの結果はそのまま会話に載り, 次のリクエストのコンテキストを消費する。外部から大きなデータを返すToolは打ち切りや要約を入れる。
