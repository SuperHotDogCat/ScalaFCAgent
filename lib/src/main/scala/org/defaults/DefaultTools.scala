package org.scalafcagent.tool.defaults

import org.scalafcagent.tool.Tool

// --------------------
// DefaultTools
// --------------------

// よく使うToolの組み合わせ
// SWE-agent (https://arxiv.org/abs/2405.15793) は, shellだけでなく
// ファイル検索・表示・編集の専用Toolを持たせるとエージェントの成績が上がると報告している
object DefaultTools {

  // 読み取り専用: find_file / search_dir / search_file / view_file
  def readOnly(workspace: Workspace): Vector[Tool] =
    Vector(
      new FindFileTool(workspace),
      new SearchDirTool(workspace),
      new SearchFileTool(workspace),
      new ViewFileTool(workspace)
    )

  // ファイル操作: readOnly + create_file / edit_file (shellは含めない)
  def fileSystem(workspace: Workspace): Vector[Tool] =
    readOnly(workspace) ++ Vector(
      new CreateFileTool(workspace),
      new EditFileTool(workspace)
    )

  // すべて: fileSystem + shell
  // shellはLLMが生成したコマンドをそのまま実行するので, 隔離された環境でだけ使うこと
  def all(workspace: Workspace): Vector[Tool] =
    fileSystem(workspace) :+ new ShellTool(workspace)
}
