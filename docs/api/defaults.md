# org.scalafcagent.tool.defaults

よく使うToolの実装 (デフォルトTool)。`lib`モジュール。

```scala
import org.scalafcagent.tool.defaults.{DefaultTools, Workspace}
```

[SWE-agent](https://arxiv.org/abs/2405.15793) は, エージェントにshellだけを渡すより, ファイルの検索・表示・編集に特化したTool
(`find_file` / `search_file` / `search_dir` / `edit`など) を渡したほうが成績が上がると報告している。
このパッケージはその考え方にならい, 次の方針でToolを実装している。

- 出力は簡潔にし, 長すぎる結果は返さずに絞り込みを促す (`Please narrow your search.`)
- ファイルは一度に`windowSize`行 (既定100行) だけ, 行番号付きで見せる
- 編集の後は編集箇所の周辺を行番号付きで返し, モデルが結果を確かめられるようにする
- 失敗は`ToolResult.Error`でモデルに返し, やり直させる

| 名前 | Tool名 | 内容 |
| --- | --- | --- |
| [`DefaultTools`](#defaulttools) | — | Toolの組み合わせ (`readOnly` / `fileSystem` / `all`) |
| [`Workspace`](#workspace) | — | Toolが扱うディレクトリ。外へのアクセスを拒否する |
| [`FindFileTool`](#findfiletool) | `find_file` | ファイル名 (glob可) でファイルを探す |
| [`SearchDirTool`](#searchdirtool) | `search_dir` | ディレクトリ以下を文字列検索し, ファイルごとの一致数を返す |
| [`SearchFileTool`](#searchfiletool) | `search_file` | 1ファイルを文字列検索し, 一致行を行番号付きで返す |
| [`ViewFileTool`](#viewfiletool) | `view_file` | ファイルの一部を行番号付きで表示する。ディレクトリなら一覧 |
| [`CreateFileTool`](#createfiletool) | `create_file` | ファイルを作る (上書きは`overwrite: true`のときだけ) |
| [`EditFileTool`](#editfiletool) | `edit_file` | 1回だけ現れる`old_str`を`new_str`に置き換える |
| [`ShellTool`](#shelltool) | `shell` | 1行のコマンドを`sh -c`で実行する |

**Example:**

```scala
import java.nio.file.Paths
import org.scalafcagent.agent.Agent
import org.scalafcagent.tool.defaults.{DefaultTools, Workspace}

val workspace = Workspace(Paths.get("/path/to/repo"))

Agent.create(llm, DefaultTools.fileSystem(workspace): _*).use { agent =>
  agent.send("Agent.scalaのsendの実装を説明して").flatMap(IO.println)
}
```

自作のToolと混ぜるときは`DefaultTools.readOnly(workspace) :+ new MyTool`のように`Vector`をつなげる。

---

## `DefaultTools`

```scala
object DefaultTools {
  def readOnly(workspace: Workspace): Vector[Tool]
  def fileSystem(workspace: Workspace): Vector[Tool]
  def all(workspace: Workspace): Vector[Tool]
}
```

| メソッド | 含むTool |
| --- | --- |
| `readOnly` | `find_file`, `search_dir`, `search_file`, `view_file` |
| `fileSystem` | `readOnly` + `create_file`, `edit_file` |
| `all` | `fileSystem` + `shell` |

!!! danger "`shell`は隔離された環境でだけ使うこと"
    `shell`はLLMが生成したコマンドをそのまま実行する。`Workspace`の制限も効かず, 実行ユーザーの権限で何でも動く。
    隔離されていない環境では`all`ではなく`fileSystem`か`readOnly`を使う。

---

## `Workspace`

```scala
case class Workspace(
  root: Path,
  maxOutput: Int = 8000,
  ignoredDirectories: Set[String] = Workspace.DefaultIgnoredDirectories
)

object Workspace {
  def current: Workspace   // カレントディレクトリ (user.dir) をrootにする
}
```

デフォルトToolが扱うディレクトリ。

- モデルが渡したパスは`root`からの相対パスとして解決する。`..`や絶対パス, シンボリックリンクで`root`の外を指すと`ToolResult.Error`になる
- 結果に出るパスは`root`からの相対パス
- Toolの結果は`maxOutput`文字で打ち切る
- `find_file` / `search_dir`は`ignoredDirectories` (既定: `.git`, `build`, `target`, `node_modules`など) に潜らない
- 先頭にNULバイトを含むファイルはバイナリとみなし, 検索・表示・編集の対象にしない

---

## `FindFileTool`

```scala
class FindFileTool(workspace: Workspace, maxResults: Int = 100)
```

| 引数 | 型 | 内容 |
| --- | --- | --- |
| `file_name` | string | ファイル名またはglob (`Agent.scala`, `*Spec.scala`) |
| `dir` | string (省略可) | 探すディレクトリ。既定は`.` |

```
Found 2 matches for "*.scala" in .:
src/Main.scala
src/Util.scala
```

`maxResults`を超えたら一覧を返さず, 絞り込みを促す。

## `SearchDirTool`

```scala
class SearchDirTool(workspace: Workspace, maxFiles: Int = 100, maxScannedFiles: Int = 20000)
```

| 引数 | 型 | 内容 |
| --- | --- | --- |
| `search_term` | string | 探す文字列 (正規表現ではない。大文字小文字を区別する) |
| `dir` | string (省略可) | 探すディレクトリ。既定は`.` |

```
Found 4 matches for "hello" in src:
src/Main.scala (1 match)
src/Util.scala (3 matches)
```

一致したファイルが`maxFiles`を超えたら一覧を返さず, 絞り込みを促す。

## `SearchFileTool`

```scala
class SearchFileTool(workspace: Workspace, maxLines: Int = 100)
```

| 引数 | 型 | 内容 |
| --- | --- | --- |
| `search_term` | string | 探す文字列 |
| `file` | string | 探すファイル |

```
Found 2 matches for "hello" in src/Util.scala:
Line 2:   val greeting = "hello"
Line 3:   val other = "hello hello"
```

## `ViewFileTool`

```scala
class ViewFileTool(workspace: Workspace, windowSize: Int = 100)
```

| 引数 | 型 | 内容 |
| --- | --- | --- |
| `path` | string | ファイルまたはディレクトリ |
| `start_line` | integer (省略可) | 最初の行 (1始まり)。既定は1 |
| `end_line` | integer (省略可) | 最後の行 (含む)。既定と上限は`start_line + windowSize - 1` |

```
[File: src/Main.scala (4 lines total)]
(1 more lines above)
2:   def main(args: Array[String]): Unit =
3:     println("hello")
(1 more lines below)
```

ディレクトリを渡すと直下の一覧 (ディレクトリは末尾に`/`) を返す。

## `CreateFileTool`

```scala
class CreateFileTool(workspace: Workspace)
```

| 引数 | 型 | 内容 |
| --- | --- | --- |
| `path` | string | 作るファイル。親ディレクトリも作る |
| `content` | string | ファイルの内容全体 |
| `overwrite` | boolean (省略可) | 既存のファイルを置き換えるか。既定は`false` |

既存のファイルは`overwrite: true`のときだけ上書きする。部分的な変更には`edit_file`を使わせる。

## `EditFileTool`

```scala
class EditFileTool(workspace: Workspace, contextLines: Int = 4)
```

| 引数 | 型 | 内容 |
| --- | --- | --- |
| `path` | string | 編集するファイル |
| `old_str` | string | 置き換える文字列。空白・インデントまで一致し, ちょうど1回現れること |
| `new_str` | string | 置き換え後の文字列。空なら削除 |

成功すると編集箇所の前後`contextLines`行を行番号付きで返す。

```
Edited src/Util.scala. Lines 1-6 after the edit:
1: object Util {
2:   val greeting = "hi"
...
```

- `old_str`が見つからない, または複数回現れるときは`ToolResult.Error`になる (複数のときは該当する行番号を返す)
- SWE-agentの`edit`は行範囲を指定するが, ここでは文字列置換にしている。モデルが行番号を数え間違えても誤った箇所を書き換えないため
- CRLFのファイルにLFの`old_str`が来た場合はCRLFに揃えて置き換える

## `ShellTool`

```scala
class ShellTool(workspace: Workspace, timeout: FiniteDuration = 30.seconds)
```

| 引数 | 型 | 内容 |
| --- | --- | --- |
| `command` | string | 1行のコマンド |

- `workspace.root`で`sh -c`として実行し, 標準出力と標準エラーをまとめて返す。標準入力は`/dev/null`
- 終了コードが0以外なら`ToolResult.Error("exit code N\n出力")`, タイムアウトなら強制終了して`ToolResult.Error("timeout after Ns\n出力")`

実装の解説は[Shellを実行するTool](../examples/tools/shell_tool.md)を参照 (引数名が`s`である点を除いてほぼ同じ)。
