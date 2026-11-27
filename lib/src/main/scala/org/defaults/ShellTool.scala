package org.scalafcagent.tool.defaults

import java.io.FileInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import org.scalafcagent.tool.Schema
import org.scalafcagent.tool.ToolResult
import org.scalafcagent.tool.TypedTool

case class ShellArguments(
  command: String
)

// --------------------
// shell
// --------------------

// 1行のコマンドを sh -c でworkspaceのrootで実行し, 標準出力と標準エラーをまとめて返す
// LLMが生成したコマンドをそのまま実行するので, 隔離された環境でだけ登録すること
class ShellTool(
  workspace: Workspace,
  timeout: FiniteDuration = 30.seconds
) extends TypedTool[ShellArguments] {

  override val name: String =
    "shell"

  override val description: String =
    "Run a one-line shell command with `sh -c` in the workspace root and return stdout and stderr combined. " +
      "Non-interactive commands only. Prefer find_file / search_dir / search_file / view_file / edit_file for files."

  override val argumentJSONSchema: String =
    """{"type":"object","properties":{"command":{"type":"string","description":"One-line shell command"}},"required":["command"]}"""

  override val schema: Schema[ShellArguments] =
    Internal.schema(deriveDecoder[ShellArguments], deriveEncoder[ShellArguments])

  override def run(args: ShellArguments): IO[ToolResult] =
    IO.blocking(Files.createTempFile("scalafc-shell-", ".log")).flatMap { outputFile =>
      val action =
        for {
          // パイプが詰まらないよう, 出力はファイルへ逃がす
          process <- IO.blocking {
            new ProcessBuilder("sh", "-c", args.command)
              .directory(workspace.absoluteRoot.toFile)
              .redirectErrorStream(true)
              .redirectOutput(outputFile.toFile)
              .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
              .start()
          }
          finished <- IO.blocking(process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS))
          _ <-
            if (finished) IO.unit
            else IO.blocking(process.destroyForcibly()) *> IO.blocking(process.waitFor()).void
          code <- IO.blocking(process.exitValue())
          output <- IO.blocking(readLimited(outputFile))
        } yield toResult(finished, code, output)

      // 成功しても失敗しても一時ファイルを消す
      action.guarantee(IO.blocking(Files.deleteIfExists(outputFile)).void)
    }

  private def readLimited(path: Path): String = {
    val in = new FileInputStream(path.toFile)
    try workspace.truncate(new String(in.readNBytes(workspace.maxOutput * 4 + 1), UTF_8))
    finally in.close()
  }

  private def toResult(finished: Boolean, code: Int, output: String): ToolResult = {
    val body =
      if (output.trim.isEmpty) "(no output)"
      else output.trim

    if (!finished) ToolResult.Error(s"timeout after ${timeout.toSeconds}s\n$body")
    // 失敗もモデルに返して, やり直させる
    else if (code != 0) ToolResult.Error(s"exit code $code\n$body")
    else ToolResult.Success(body)
  }
}
