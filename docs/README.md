# ドキュメント

ScalaFCAgent (function-calling-made-by-FP) のドキュメント。

- [Quick Start](./quickstart.md) — 最小構成から動かすまで
- [Examples](./examples/README.md) — アプリケーション例 (対話CLI, 型付きTool, HTTPサーバ, ChatFormat, テスト)
- [API Reference](./api/README.md) — 各APIの解説・返り値・使用例
  - [agent](./api/agent.md) / [tool](./api/tool.md) / [chat](./api/chat.md) / [llm](./api/llm.md) / [client](./api/client.md) / [server](./api/server.md)

## モジュール構成

| モジュール | 内容 | 主な依存 |
| --- | --- | --- |
| `lib` | Function Callingの中核。UserとLLMServerを仲介する`Agent`を提供 | cats-effect, circe |
| `server` | HTTPリクエストを受け付けるラッパー | lib, Pekko HTTP |

`lib`はライブラリとして使う。CLI/HTTPなどの入口は`lib`に置かず`server`側に分けている。
