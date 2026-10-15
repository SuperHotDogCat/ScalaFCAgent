# ドキュメント

ScalaFCAgent (function-calling-made-by-FP) のドキュメント。

- [Quick Start](./quickstart.md) — 最小構成から動かすまで
- Reference
  - [コア (Agent / Chat / Tool / LLMServer / Client)](./reference/core.md)
  - [HTTPサーバ (server)](./reference/http-server.md)

## モジュール構成

| モジュール | 内容 | 主な依存 |
| --- | --- | --- |
| `lib` | Function Callingの中核。UserとLLMServerを仲介する`Agent`を提供 | cats-effect, circe |
| `server` | HTTPリクエストを受け付けるラッパー | lib, Pekko HTTP |

`lib`はライブラリとして使う。CLI/HTTPなどの入口は`lib`に置かず`server`側に分けている。
