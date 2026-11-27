# Examples

ScalaFCAgentを使ったアプリケーション例。どれも手元のOpenAI互換サーバ (既定はOllama `http://localhost:11434`) で動く。

各ページには, 例の目的・実行方法・ソースコード・要点をまとめている。
APIの詳細は[API Reference](../api/README.md)を参照。

## Basic

- **[対話CLIエージェント](./basic/interactive_cli.md)** — `Agent`にToolを登録し, 1回だけの質問 / 対話モード (REPL) / ストリーミング表示を切り替えるCLI。Tool呼び出しの往復を`agent.history`から表示する。

## Tools

- **[型付きToolを作る](./tools/typed_tools.md)** — `TypedTool[A]`と`Schema[A]`で, 現在時刻・天気・四則演算のToolを定義する。
- **[Shellを実行するTool](./tools/shell_tool.md)** — LLMが生成したコマンドを`sh -c`で実行するTool。タイムアウト, 出力の打ち切り, 失敗をモデルに返してやり直させる方法。

## Serving

- **[OpenAI互換HTTPサーバ](./serving/openai_compatible_server.md)** — `server`モジュールでAgentを`POST /v1/chat/completions`として公開し, curlやOpenAI SDKから呼ぶ。

## Features

- **[モデルごとのTool呼び出し形式 (ChatFormat)](./features/custom_chat_format.md)** — Hermes / Qwen3-Coder / DeepSeek / Gemma 3など, モデル固有のTool呼び出し形式に合わせて`ChatFormat`を差し替える。

## Testing

- **[モックLLMServerでテストする](./testing/mock_llm_server.md)** — `LLMServer.apply`と`Agent.from`で, LLMを立てずにTool呼び出しの往復をテストする。

## ソースの場所

| 例 | ソース |
| --- | --- |
| 対話CLIエージェント / 型付きTool / Shell Tool | 別リポジトリ `scalafctest` (`src/main/scala/example/`) |
| OpenAI互換HTTPサーバ | [`server/src/test/scala/org/example/OllamaExample.scala`](https://github.com/SuperHotDogCat/ScalaFCAgent/blob/main/server/src/test/scala/org/example/OllamaExample.scala) |
| ChatFormat | [`lib/src/test/scala/org/example/ChatFormat.scala`](https://github.com/SuperHotDogCat/ScalaFCAgent/blob/main/lib/src/test/scala/org/example/ChatFormat.scala) |
| モックLLMServer | [`lib/src/test/scala/org/example/Agent.scala`](https://github.com/SuperHotDogCat/ScalaFCAgent/blob/main/lib/src/test/scala/org/example/Agent.scala) |
