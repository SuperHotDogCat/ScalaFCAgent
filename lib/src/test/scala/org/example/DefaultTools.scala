package org.scalafcagent.tool.defaults

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._

import cats.effect.unsafe.implicits.global
import org.junit.runner.RunWith
import org.scalafcagent.tool.ToolCall
import org.scalafcagent.tool.ToolEngine
import org.scalafcagent.tool.ToolResult
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class DefaultToolsSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach {

  private var root: Path = _
  private var engine: ToolEngine = _

  override def beforeEach(): Unit = {
    root = Files.createTempDirectory("scalafc-defaults-")
    write("src/Main.scala", "object Main {\n  def main(args: Array[String]): Unit =\n    println(\"hello\")\n}\n")
    write("src/Util.scala", "object Util {\n  val greeting = \"hello\"\n  val other = \"hello hello\"\n}\n")
    write("README.md", "# demo\n")
    write("build/Generated.scala", "hello\n")
    engine = new ToolEngine(DefaultTools.all(Workspace(root)): _*)
  }

  override def afterEach(): Unit =
    Files.walk(root).iterator.asScala.toVector.reverse.foreach(Files.deleteIfExists)

  private def write(path: String, content: String): Unit = {
    val file = root.resolve(path)
    Files.createDirectories(file.getParent)
    Files.write(file, content.getBytes(UTF_8))
  }

  private def read(path: String): String =
    new String(Files.readAllBytes(root.resolve(path)), UTF_8)

  private def call(name: String, arguments: String): ToolResult =
    engine.call(ToolCall(name, arguments)).unsafeRunSync()

  private def success(result: ToolResult): String =
    result match {
      case ToolResult.Success(value) => value
      case ToolResult.Error(message) => fail(s"Expected Success but got Error: $message")
    }

  private def error(result: ToolResult): String =
    result match {
      case ToolResult.Error(message) => message
      case ToolResult.Success(value) => fail(s"Expected Error but got Success: $value")
    }

  test("find_file matches globs and skips ignored directories") {
    success(call("find_file", """{"file_name":"*.scala"}""")) shouldBe
      "Found 2 matches for \"*.scala\" in .:\nsrc/Main.scala\nsrc/Util.scala"

    success(call("find_file", """{"file_name":"Nope.scala"}""")) should startWith("No matches found")
  }

  test("search_dir counts matches per file") {
    success(call("search_dir", """{"search_term":"hello","dir":"src"}""")) shouldBe
      "Found 4 matches for \"hello\" in src:\nsrc/Main.scala (1 match)\nsrc/Util.scala (3 matches)"
  }

  test("search_file returns matching lines with line numbers") {
    success(call("search_file", """{"search_term":"hello","file":"src/Util.scala"}""")) shouldBe
      "Found 2 matches for \"hello\" in src/Util.scala:\nLine 2:   val greeting = \"hello\"\nLine 3:   val other = \"hello hello\""
  }

  test("view_file shows a window with line numbers") {
    success(call("view_file", """{"path":"src/Main.scala","start_line":2,"end_line":3}""")) shouldBe
      "[File: src/Main.scala (4 lines total)]\n(1 more lines above)\n2:   def main(args: Array[String]): Unit =\n3:     println(\"hello\")\n(1 more lines below)"
  }

  test("view_file lists a directory") {
    success(call("view_file", """{"path":"."}""")) shouldBe "[Directory: .]\nREADME.md\nbuild/\nsrc/"
  }

  test("paths outside the workspace are rejected") {
    error(call("view_file", """{"path":"../outside.txt"}""")) should startWith("Path is outside the workspace")
    error(call("create_file", """{"path":"/tmp/x.txt","content":"x"}""")) should startWith("Path is outside the workspace")
  }

  test("create_file does not overwrite unless asked") {
    success(call("create_file", """{"path":"new/dir/A.txt","content":"a\nb\n"}""")) shouldBe "Wrote new/dir/A.txt (2 lines)"
    read("new/dir/A.txt") shouldBe "a\nb\n"

    error(call("create_file", """{"path":"README.md","content":"x"}""")) should startWith("File already exists")
    success(call("create_file", """{"path":"README.md","content":"x","overwrite":true}"""))
    read("README.md") shouldBe "x"
  }

  test("edit_file replaces a unique occurrence and shows the result") {
    val result = success(call("edit_file", """{"path":"src/Util.scala","old_str":"val greeting = \"hello\"","new_str":"val greeting = \"hi\""}"""))

    read("src/Util.scala") should include("val greeting = \"hi\"")
    result should startWith("Edited src/Util.scala.")
    result should include("2:   val greeting = \"hi\"")
  }

  test("edit_file rejects missing and ambiguous old_str") {
    error(call("edit_file", """{"path":"src/Util.scala","old_str":"nope","new_str":"x"}""")) should startWith("old_str was not found")
    error(call("edit_file", """{"path":"src/Util.scala","old_str":"val ","new_str":"var "}""")) shouldBe
      "old_str occurs 2 times in src/Util.scala (lines 2, 3). Include more surrounding text to make it unique."
    read("src/Util.scala") should include("val other")
  }

  test("edit_file keeps CRLF line endings") {
    write("win.txt", "a\r\nb\r\nc\r\n")
    success(call("edit_file", """{"path":"win.txt","old_str":"a\nb","new_str":"x\ny"}"""))
    read("win.txt") shouldBe "x\r\ny\r\nc\r\n"
  }

  test("shell runs in the workspace root and reports failures") {
    success(call("shell", """{"command":"ls src"}""")) shouldBe "Main.scala\nUtil.scala"
    error(call("shell", """{"command":"exit 3"}""")) shouldBe "exit code 3\n(no output)"
  }
}
