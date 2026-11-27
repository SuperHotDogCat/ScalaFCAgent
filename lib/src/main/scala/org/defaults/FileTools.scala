package org.scalafcagent.tool.defaults

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import scala.jdk.CollectionConverters._
import scala.util.Using

import cats.effect.IO
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import org.scalafcagent.tool.Schema
import org.scalafcagent.tool.ToolResult
import org.scalafcagent.tool.TypedTool

// --------------------
// view_file
// --------------------

case class ViewFileArguments(
  path: String,
  start_line: Option[Int],
  end_line: Option[Int]
)

// ファイルを行番号付きで一部 (window) だけ表示する. ディレクトリなら直下の一覧を返す
// SWE-agentの open / goto / scroll に相当し, 一度に見せる行数を絞ってコンテキストを節約する
class ViewFileTool(
  workspace: Workspace,
  windowSize: Int = 100
) extends TypedTool[ViewFileArguments] {

  override val name: String =
    "view_file"

  override val description: String =
    s"Show a text file with line numbers, at most $windowSize lines at a time starting at start_line (default 1). " +
      "Pass start_line/end_line to see other parts. If path is a directory, list its entries."

  override val argumentJSONSchema: String =
    """{"type":"object","properties":{"path":{"type":"string","description":"File or directory path"},"start_line":{"type":"integer","description":"First line to show (1-based)"},"end_line":{"type":"integer","description":"Last line to show (inclusive)"}},"required":["path"]}"""

  override val schema: Schema[ViewFileArguments] =
    Internal.schema(deriveDecoder[ViewFileArguments], deriveEncoder[ViewFileArguments])

  override def run(args: ViewFileArguments): IO[ToolResult] =
    Internal.result {
      workspace.resolve(args.path).flatMap { path =>
        if (Files.isDirectory(path)) Right(listDirectory(path))
        else if (!Files.isRegularFile(path)) Left(s"File not found: ${args.path}")
        else if (!workspace.isText(path)) Left(s"Not a text file: ${args.path}")
        else window(path, args)
      }
    }

  private def listDirectory(dir: java.nio.file.Path): String = {
    val entries =
      Using.resource(Files.list(dir))(_.iterator.asScala.toVector)
        .map(p => if (Files.isDirectory(p)) s"${p.getFileName}/" else p.getFileName.toString)
        .sorted

    val body = if (entries.isEmpty) "(empty)" else entries.mkString("\n")
    workspace.truncate(s"[Directory: ${workspace.display(dir)}]\n$body")
  }

  private def window(path: java.nio.file.Path, args: ViewFileArguments): Either[String, String] = {
    val lines = Internal.lines(workspace.readText(path))
    val total = lines.size
    val start = args.start_line.getOrElse(1).max(1)
    val end = args.end_line.getOrElse(start + windowSize - 1).min(start + windowSize - 1).min(total)
    val header = s"[File: ${workspace.display(path)} (${Internal.plural(total, "line")} total)]"

    if (total == 0) {
      Right(s"$header\n(empty file)")
    } else if (start > total) {
      Left(s"start_line $start is beyond the end of the file (${Internal.plural(total, "line")})")
    } else if (end < start) {
      Left(s"end_line ${args.end_line.getOrElse(end)} is before start_line $start")
    } else {
      val above = if (start > 1) List(s"(${start - 1} more lines above)") else Nil
      val below = if (end < total) List(s"(${total - end} more lines below)") else Nil
      val body = Internal.numbered(lines.slice(start - 1, end), start)
      Right(workspace.truncate((header :: above ::: body :: below).mkString("\n")))
    }
  }
}


// --------------------
// create_file
// --------------------

case class CreateFileArguments(
  path: String,
  content: String,
  overwrite: Option[Boolean]
)

// ファイルを新しく作る. 既存のファイルはoverwrite = trueのときだけ上書きする
class CreateFileTool(
  workspace: Workspace
) extends TypedTool[CreateFileArguments] {

  override val name: String =
    "create_file"

  override val description: String =
    "Create a new file with the given content (parent directories are created). " +
      "Fails if the file exists unless overwrite is true; use edit_file to change part of an existing file."

  override val argumentJSONSchema: String =
    """{"type":"object","properties":{"path":{"type":"string","description":"Path of the file to create"},"content":{"type":"string","description":"Full content of the file"},"overwrite":{"type":"boolean","description":"Replace the file if it already exists (default: false)"}},"required":["path","content"]}"""

  override val schema: Schema[CreateFileArguments] =
    Internal.schema(deriveDecoder[CreateFileArguments], deriveEncoder[CreateFileArguments])

  override def run(args: CreateFileArguments): IO[ToolResult] =
    Internal.result {
      workspace.resolve(args.path).flatMap { path =>
        if (Files.isDirectory(path)) {
          Left(s"Path is a directory: ${args.path}")
        } else if (Files.exists(path) && !args.overwrite.getOrElse(false)) {
          Left(s"File already exists: ${args.path}. Use edit_file, or set overwrite to true to replace it.")
        } else {
          Option(path.getParent).foreach(Files.createDirectories(_))
          Files.write(path, args.content.getBytes(UTF_8))
          Right(s"Wrote ${workspace.display(path)} (${Internal.plural(Internal.lines(args.content).size, "line")})")
        }
      }
    }
}


// --------------------
// edit_file
// --------------------

case class EditFileArguments(
  path: String,
  old_str: String,
  new_str: String
)

// ファイル中でちょうど1回現れるold_strをnew_strに置き換え, 編集箇所の周辺を行番号付きで返す
// 行番号で範囲を指定する方式より, モデルが範囲を数え間違えにくい
class EditFileTool(
  workspace: Workspace,
  contextLines: Int = 4
) extends TypedTool[EditFileArguments] {

  override val name: String =
    "edit_file"

  override val description: String =
    "Replace old_str with new_str in a file. old_str must match the file exactly (including whitespace and indentation) " +
      "and occur exactly once; include surrounding lines to make it unique. new_str may be empty to delete. " +
      "Returns the edited region with line numbers."

  override val argumentJSONSchema: String =
    """{"type":"object","properties":{"path":{"type":"string","description":"Path of the file to edit"},"old_str":{"type":"string","description":"Exact text to replace (must occur exactly once)"},"new_str":{"type":"string","description":"Replacement text"}},"required":["path","old_str","new_str"]}"""

  override val schema: Schema[EditFileArguments] =
    Internal.schema(deriveDecoder[EditFileArguments], deriveEncoder[EditFileArguments])

  override def run(args: EditFileArguments): IO[ToolResult] =
    Internal.result {
      for {
        _ <- Either.cond(args.old_str.nonEmpty, (), "old_str must not be empty. Use create_file to write a whole file.")
        path <- workspace.resolve(args.path)
        _ <- Either.cond(Files.isRegularFile(path), (), s"File not found: ${args.path}")
        _ <- Either.cond(workspace.isText(path), (), s"Not a text file: ${args.path}")
        text = workspace.readText(path)
        strs = matchLineEndings(text, args)
        oldStr = strs._1
        newStr = strs._2
        index <- uniqueIndex(text, oldStr, args.path)
      } yield {
        val edited = text.substring(0, index) + newStr + text.substring(index + oldStr.length)
        Files.write(path, edited.getBytes(UTF_8))
        snippet(path, edited, index, newStr)
      }
    }

  // CRLFのファイルにLFのold_strが来たら, CRLFに揃える
  private def matchLineEndings(text: String, args: EditFileArguments): (String, String) =
    if (!text.contains(args.old_str) && text.contains("\r\n") && !args.old_str.contains("\r\n"))
      (args.old_str.replace("\n", "\r\n"), args.new_str.replace("\n", "\r\n"))
    else
      (args.old_str, args.new_str)

  private def uniqueIndex(text: String, oldStr: String, displayPath: String): Either[String, Int] = {
    val indices =
      Iterator
        .iterate(text.indexOf(oldStr))(i => text.indexOf(oldStr, i + 1))
        .takeWhile(_ >= 0)
        .take(11)
        .toVector

    indices match {
      case Vector(index) =>
        Right(index)

      case Vector() =>
        Left(s"old_str was not found in $displayPath. Use view_file to check the exact text (including whitespace).")

      case many =>
        val lineNumbers = many.take(10).map(lineOf(text, _)).mkString(", ")
        val more = if (many.size > 10) ", ..." else ""
        Left(s"old_str occurs ${many.size}${if (many.size > 10) "+" else ""} times in $displayPath (lines $lineNumbers$more). Include more surrounding text to make it unique.")
    }
  }

  private def lineOf(text: String, index: Int): Int =
    text.substring(0, index).count(_ == '\n') + 1

  private def snippet(path: java.nio.file.Path, edited: String, index: Int, newStr: String): String = {
    val lines = Internal.lines(edited)
    val firstChanged = lineOf(edited, index)
    val lastChanged = lineOf(edited, index + newStr.length)
    val from = (firstChanged - contextLines).max(1)
    val to = (lastChanged + contextLines).min(lines.size)
    val body =
      if (lines.isEmpty) "(empty file)"
      else Internal.numbered(lines.slice(from - 1, to), from)

    workspace.truncate(s"Edited ${workspace.display(path)}. Lines $from-$to after the edit:\n$body")
  }
}
