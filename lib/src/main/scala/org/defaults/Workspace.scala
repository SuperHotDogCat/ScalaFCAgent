package org.scalafcagent.tool.defaults

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.mutable.ListBuffer

import cats.effect.IO
import io.circe.Decoder
import io.circe.Encoder
import org.scalafcagent.tool.Schema
import org.scalafcagent.tool.ToolResult

// --------------------
// Workspace
// --------------------

// デフォルトToolが扱うディレクトリ
// Toolに渡るパスはすべてrootからの相対パスとして解決し, rootの外は扱わない
case class Workspace(
  root: Path,
  // Toolの結果 (会話に載る文字列) の最大文字数
  maxOutput: Int = 8000,
  // 一覧・検索で辿らないディレクトリ
  ignoredDirectories: Set[String] = Workspace.DefaultIgnoredDirectories
) {

  val absoluteRoot: Path =
    root.toAbsolutePath.normalize

  // モデルが渡したパスをroot配下の絶対パスにする
  def resolve(path: String): Either[String, Path] = {
    val candidate = absoluteRoot.resolve(path).normalize

    if (!candidate.startsWith(absoluteRoot)) {
      Left(s"Path is outside the workspace: $path")
    } else if (escapesBySymlink(candidate)) {
      Left(s"Path is outside the workspace (symbolic link): $path")
    } else {
      Right(candidate)
    }
  }

  // モデルに見せるパス (rootからの相対パス)
  def display(path: Path): String = {
    val relative = absoluteRoot.relativize(path.toAbsolutePath.normalize).toString
    if (relative.isEmpty) "." else relative
  }

  // dir以下の通常ファイルを辿る. ignoredDirectoriesは潜らない
  // limitを超えたらそこで打ち切り, (ファイル, 打ち切ったか) を返す
  def walkFiles(dir: Path, limit: Int)(accept: Path => Boolean): (Vector[Path], Boolean) = {
    val found = ListBuffer.empty[Path]
    var truncated = false

    Files.walkFileTree(dir, new SimpleFileVisitor[Path] {

      override def preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult =
        if (d != dir && ignoredDirectories.contains(d.getFileName.toString)) FileVisitResult.SKIP_SUBTREE
        else FileVisitResult.CONTINUE

      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
        if (!attrs.isRegularFile || !accept(file)) {
          FileVisitResult.CONTINUE
        } else if (found.size >= limit) {
          truncated = true
          FileVisitResult.TERMINATE
        } else {
          found += file
          FileVisitResult.CONTINUE
        }

      // 読めないファイル・ディレクトリは飛ばす
      override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
        FileVisitResult.CONTINUE
    })

    (found.toVector.sortBy(_.toString), truncated)
  }

  // 先頭にNULバイトが無ければテキストとみなす
  def isText(file: Path): Boolean = {
    val in: InputStream = Files.newInputStream(file)
    try !in.readNBytes(8000).contains(0.toByte)
    finally in.close()
  }

  def readText(file: Path): String =
    new String(Files.readAllBytes(file), UTF_8)

  // 長すぎる出力は先頭だけ返す (Toolの結果は次のリクエストのコンテキストを消費する)
  def truncate(text: String): String =
    if (text.length <= maxOutput) text
    else text.take(maxOutput) + s"\n...(truncated, ${text.length - maxOutput} more characters)"

  private def escapesBySymlink(candidate: Path): Boolean =
    Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && {
      val realRoot = absoluteRoot.toRealPath()
      // リンク切れなどで解決できない場合は外とみなす
      try !candidate.toRealPath().startsWith(realRoot)
      catch { case _: IOException => true }
    }
}

object Workspace {

  val DefaultIgnoredDirectories: Set[String] =
    Set(".git", ".gradle", ".idea", ".bsp", ".metals", "build", "target", "node_modules", "__pycache__", ".venv")

  // カレントディレクトリをrootにする
  def current: Workspace =
    Workspace(Paths.get(System.getProperty("user.dir")))
}


// --------------------
// 内部ユーティリティ
// --------------------

private[defaults] object Internal {

  def schema[A](implicit d: Decoder[A], e: Encoder[A]): Schema[A] =
    new Schema[A] {
      implicit val decoder: Decoder[A] = d
      implicit val encoder: Encoder[A] = e
    }

  // 行に分ける. 末尾の改行は空行として数えない
  def lines(text: String): Vector[String] = {
    val all = text.split("\n", -1).toVector.map(_.stripSuffix("\r"))
    if (all.lastOption.contains("")) all.init else all
  }

  def numbered(lines: Seq[String], firstLineNumber: Int): String =
    lines.zipWithIndex
      .map { case (line, i) => s"${firstLineNumber + i}: $line" }
      .mkString("\n")

  // ファイル操作をblockingで実行し, 失敗 (Left・例外) はモデルに返すToolResult.Errorにする
  def result(body: => Either[String, String]): IO[ToolResult] =
    IO.blocking(body).attempt.map {
      case Right(Right(value)) => ToolResult.Success(value)
      case Right(Left(message)) => ToolResult.Error(message)
      case Left(error) => ToolResult.Error(Option(error.getMessage).getOrElse(error.toString))
    }

  def plural(n: Int, word: String): String =
    if (n == 1) s"1 $word"
    else if (word.endsWith("ch") || word.endsWith("sh")) s"$n ${word}es"
    else s"$n ${word}s"
}
