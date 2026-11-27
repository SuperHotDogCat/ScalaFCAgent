# API Reference

## Summary

ScalaFCAgentの公開API。アプリケーションから主に使うのは`Agent`と`TypedTool`で, それ以外は差し替えやテストのために公開されている。

### 全体の流れ

```
User → Agent → Client ──(messages)──▶ LLMServer ──▶ OpenAI互換API
                   │                        │
                   │◀────── output ─────────┘
                   ▼
                 Chat ──▶ ToolEngine ──▶ Tool
                   ▲                        │
                   └──── <tool_result> ◀────┘
```

1. `Agent.send`がUserの発話を会話 (`Chat`) に追加し, `LLMServer`へ送る
2. 出力に`<tool>`があれば`ToolEngine`がToolを実行し, 結果を`<tool_result>`として会話に追加して再び送る
3. 出力にTool呼び出しがなくなったら, その出力を返す

### Agent

UserとLLMServerの間を仲介する公開API。`lib`モジュール, パッケージ`org.scalafcagent.agent`。

- [`Agent`](./agent.md#agent) — 1つの会話に対応するエージェント。`send` / `sendStream` / `history` / `reset`
- [`Agent.create`](./agent.md#agentcreate) — `LLMServerConfig`とToolから作る
- [`Agent.from`](./agent.md#agentfrom) — 任意の`LLMServer`から作る (テスト・独自トランスポート)
- [`AgentConfig`](./agent.md#agentconfig) — system prompt, Tool呼び出し形式, 呼び出し回数の上限

### Tool

Toolの定義と実行。`lib`モジュール, パッケージ`org.scalafcagent.tool`。

- [`TypedTool[A]`](./tool.md#typedtool) — 型付きTool。アプリケーションはこれを継承する
- [`Schema[A]`](./tool.md#schema) — 引数のcase classとJSON文字列の相互変換
- [`ToolResult`](./tool.md#toolresult) — Toolの実行結果 (`Success` / `Error`)
- [`Tool`](./tool.md#tool) — 型消去されたTool
- [`ToolCall`](./tool.md#toolcall) — モデル出力から取り出したTool呼び出し
- [`ToolEngine`](./tool.md#toolengine) — 名前からToolを引いて実行する
- [`ToolError`](./tool.md#toolerror) — 引数のdecodeエラー

### Chat

会話の表現とTool呼び出しの形式。`lib`モジュール, パッケージ`org.scalafcagent.chat`。

- [`Role`](./chat.md#role) — `System` / `User` / `Assistant` / `Thinking` / `Tool`
- [`Message`](./chat.md#message) — Role付きのメッセージ
- [`ChatFormat`](./chat.md#chatformat) — Tool呼び出しの形式 (取り出し・system prompt・結果の形式)
- [`Chat`](./chat.md#chat) — immutableな会話。Tool呼び出しの抽出と実行

### LLM

LLMServerとの通信。`lib`モジュール, パッケージ`org.scalafcagent.llm`。

- [`LLMServer`](./llm.md#llmserver) — トランスポートのtrait
- [`LLMServer.apply`](./llm.md#llmserverapply) — 関数からLLMServerを作る (モック用)
- [`LLMServerConfig`](./llm.md#llmserverconfig) — 接続先・モデル・サンプリングの設定
- [`OpenAILLMServer`](./llm.md#openaillmserver) — OpenAI互換API (`/v1/chat/completions`) の実装

### Client

送受信ループ。通常は`Agent`経由で使う。`lib`モジュール, パッケージ`org.scalafcagent.client`。

- [`Client`](./client.md#client) — LLMServerへの送信とTool解決の繰り返し
- [`Client.create`](./client.md#clientcreate)

### Server

OpenAI互換のHTTPサーバ。`server`モジュール, パッケージ`org.scalafcagent.server`。

- [HTTPエンドポイント](./server.md#httpエンドポイント) — `POST /v1/chat/completions`, `GET /v1/health`
- [`HttpServer`](./server.md#httpserver) — `resource` / `from` / `Config`
- [`Routes`](./server.md#routes) — Pekko HTTPの`Route`
- [`ChatCompletionRequest`](./server.md#chatcompletionrequest) — リクエストのモデル

## 依存

| モジュール | 公開APIに出る依存 |
| --- | --- |
| `lib` | cats-effect (`IO`, `Resource`, `Ref`), fs2 (`Stream`), circe (`Decoder` / `Encoder`) |
| `server` | `lib`, Apache Pekko HTTP (`Route`, `Http.ServerBinding`) |

## 表記

- 戻り値が`IO[A]`のメソッドは, 呼んだ時点では何も実行しない。`IOApp`や`unsafeRunSync`で実行したときに副作用が起きる
- 「エラー」欄は, 戻り値の`IO` / `Stream`が失敗するときの例外を表す
