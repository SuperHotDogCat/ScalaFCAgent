package org.scalafcagent.tool.defaults

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

import cats.effect.IO
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import org.scalafcagent.tool.Schema
import org.scalafcagent.tool.ToolResult
import org.scalafcagent.tool.TypedTool

// SWE-agent (https://arxiv.org/abs/2405.15793) の find_file / search_dir / search_file にならい,
// 結果は簡潔にし, 多すぎるときは一覧を返さず絞り込みを促す

private[defaults] object Search {

  // 対象がディレクトリであることを確かめる
  def directory(workspace: Workspace, dir: Option[String]): Either[String, Path] =
    workspace.resolve(dir.getOrElse(".")).flatMap { path =>
      if (Files.isDirectory(path)) Right(path)
      else Left(s"Directory not found: ${dir.getOrElse(".")}")
    }

  def countOccurrences(text: String, term: String): Int =
    if (term.isEmpty) 0
    else Iterator
      .iterate(text.indexOf(term))(i => text.indexOf(term, i + term.length))
      .takeWhile(_ >= 0)
      .size
}


// --------------------
// find_file
// --------------------

case class FindFileArguments(
  file_name: String,
  dir: Option[String]
)

// ファイル名 (globも可) でファイルを探す
class FindFileTool(
  workspace: Workspace,
  maxResults: Int = 100
) extends TypedTool[FindFileArguments] {

  override val name: String =
    "find_file"

  override val description: String =
    "Find files whose name matches file_name (exact name or glob such as *.scala) under dir (default: workspace root). Returns relative paths."

  override val argumentJSONSchema: String =
    """{"type":"object","properties":{"file_name":{"type":"string","description":"File name or glob pattern, e.g. Agent.scala or *Spec.scala"},"dir":{"type":"string","description":"Directory to search in (default: .)"}},"required":["file_name"]}"""

  override val schema: Schema[FindFileArguments] =
    Internal.schema(deriveDecoder[FindFileArguments], deriveEncoder[FindFileArguments])

  override def run(args: FindFileArguments): IO[ToolResult] =
    Internal.result {
      Search.directory(workspace, args.dir).map { dir =>
        val matcher = FileSystems.getDefault.getPathMatcher(s"glob:${args.file_name}")
        val (files, truncated) =
          workspace.walkFiles(dir, maxResults)(file => matcher.matches(file.getFileName))
        val where = workspace.display(dir)

        if (files.isEmpty) {
          s"""No matches found for "${args.file_name}" in $where"""
        } else if (truncated) {
          s"""More than $maxResults files matched "${args.file_name}" in $where. Please narrow your search."""
        } else {
          (s"""Found ${Internal.plural(files.size, "match")} for "${args.file_name}" in $where:""" +:
            files.map(workspace.display)).mkString("\n")
        }
      }
    }
}


// --------------------
// search_dir
// --------------------

case class SearchDirArguments(
  search_term: String,
  dir: Option[String]
)

// ディレクトリ以下のテキストファイルから文字列を探し, ファイルごとの一致数を返す
class SearchDirTool(
  workspace: Workspace,
  maxFiles: Int = 100,
  maxScannedFiles: Int = 20000
) extends TypedTool[SearchDirArguments] {

  override val name: String =
    "search_dir"

  override val description: String =
    "Search for search_term (plain text, case-sensitive) in all text files under dir (default: workspace root). " +
      "Returns each matching file with its number of matches. Use search_file to see the matching lines."

  override val argumentJSONSchema: String =
    """{"type":"object","properties":{"search_term":{"type":"string","description":"Text to search for"},"dir":{"type":"string","description":"Directory to search in (default: .)"}},"required":["search_term"]}"""

  override val schema: Schema[SearchDirArguments] =
    Internal.schema(deriveDecoder[SearchDirArguments], deriveEncoder[SearchDirArguments])

  override def run(args: SearchDirArguments): IO[ToolResult] =
    Internal.result {
      if (args.search_term.isEmpty) {
        Left("search_term must not be empty")
      } else {
        Search.directory(workspace, args.dir).map { dir =>
          val (files, _) = workspace.walkFiles(dir, maxScannedFiles)(_ => true)
          val counts =
            files.iterator
              .filter(file => scala.util.Try(workspace.isText(file)).getOrElse(false))
              .flatMap { file =>
                val count =
                  scala.util.Try(workspace.readText(file)).toOption
                    .map(text => Search.countOccurrences(text, args.search_term))
                    .getOrElse(0)
                if (count > 0) Some(file -> count) else None
              }
              .toVector
          val where = workspace.display(dir)

          if (counts.isEmpty) {
            s"""No matches found for "${args.search_term}" in $where"""
          } else if (counts.size > maxFiles) {
            s"""More than $maxFiles files matched "${args.search_term}" in $where. Please narrow your search."""
          } else {
            val total = counts.map(_._2).sum
            (s"""Found ${Internal.plural(total, "match")} for "${args.search_term}" in $where:""" +:
              counts.map { case (file, n) => s"${workspace.display(file)} (${Internal.plural(n, "match")})" })
              .mkString("\n")
          }
        }
      }
    }
}


// --------------------
// search_file
// --------------------

case class SearchFileArguments(
  search_term: String,
  file: String
)

// 1つのファイルから文字列を探し, 一致した行を行番号付きで返す
class SearchFileTool(
  workspace: Workspace,
  maxLines: Int = 100
) extends TypedTool[SearchFileArguments] {

  override val name: String =
    "search_file"

  override val description: String =
    "Search for search_term (plain text, case-sensitive) in file and return the matching lines with their line numbers."

  override val argumentJSONSchema: String =
    """{"type":"object","properties":{"search_term":{"type":"string","description":"Text to search for"},"file":{"type":"string","description":"Path of the file to search"}},"required":["search_term","file"]}"""

  override val schema: Schema[SearchFileArguments] =
    Internal.schema(deriveDecoder[SearchFileArguments], deriveEncoder[SearchFileArguments])

  override def run(args: SearchFileArguments): IO[ToolResult] =
    Internal.result {
      for {
        _ <- Either.cond(args.search_term.nonEmpty, (), "search_term must not be empty")
        path <- workspace.resolve(args.file)
        _ <- Either.cond(Files.isRegularFile(path), (), s"File not found: ${args.file}")
        _ <- Either.cond(workspace.isText(path), (), s"Not a text file: ${args.file}")
      } yield {
        val matches =
          Internal.lines(workspace.readText(path))
            .zipWithIndex
            .collect { case (line, i) if line.contains(args.search_term) => s"Line ${i + 1}: $line" }
        val where = workspace.display(path)

        if (matches.isEmpty) {
          s"""No matches found for "${args.search_term}" in $where"""
        } else if (matches.size > maxLines) {
          s"""More than $maxLines lines matched "${args.search_term}" in $where. Please narrow your search."""
        } else {
          workspace.truncate(
            (s"""Found ${Internal.plural(matches.size, "match")} for "${args.search_term}" in $where:""" +: matches)
              .mkString("\n")
          )
        }
      }
    }
}
