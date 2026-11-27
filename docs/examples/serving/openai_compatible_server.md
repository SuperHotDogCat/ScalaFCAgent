# OpenAI互換HTTPサーバ

`server`モジュールの`HttpServer`で, Toolを登録したAgentをOpenAI互換の`POST /v1/chat/completions`として公開する例。
クライアントからはTool呼び出しを意識せず, 普通のChat Completion APIとして使える (Toolはサーバ側で解決される)。

```
client ──POST /v1/chat/completions──▶ HttpServer ──▶ Agent ──▶ LLMServer (Ollama / vLLM)
                                                      │
                                                      └──▶ Tool (current_time)
```

使うAPI: [`HttpServer.resource`](../../api/server.md#httpserverresource), [`HttpServer.Config`](../../api/server.md#httpserverconfig)

## 実行

リポジトリに実行例 (`OllamaExample`) がある。登録しているToolは`current_time`だけ。

```bash
OLLAMA_MODEL=gemma4:e2b ./gradlew :server:runOllamaExample
# listening on http://127.0.0.1:8080 (model=gemma4:e2b)
```

| 環境変数 | デフォルト | 内容 |
| --- | --- | --- |
| `HOST` | `127.0.0.1` | バインドするホスト |
| `PORT` | `8080` | バインドするポート |
| `OLLAMA_HOST` | `http://localhost:11434` | 接続先のOpenAI互換サーバ |
| `OLLAMA_MODEL` | `qwen3:1.7b` | モデル名 |

## 呼び出す

=== "curl"

    ```bash
    curl -s -X POST localhost:8080/v1/chat/completions \
      -H 'Content-Type: application/json' \
      -d '{"model":"gemma4:e2b","messages":[{"role":"user","content":"今の東京の時刻を教えて"}]}'
    ```

    ```json
    {
      "id": "chatcmpl-...",
      "object": "chat.completion",
      "created": 1792089308,
      "model": "gemma4:e2b",
      "choices": [
        {
          "index": 0,
          "message": {"role": "assistant", "content": "現在の東京の時刻は ... です。"},
          "finish_reason": "stop"
        }
      ]
    }
    ```

=== "curl (stream)"

    ```bash
    curl -s -N -X POST localhost:8080/v1/chat/completions \
      -H 'Content-Type: application/json' \
      -d '{"model":"gemma4:e2b","messages":[{"role":"user","content":"今の東京の時刻を教えて"}],"stream":true}'
    ```

    ```
    data: {"id":"chatcmpl-...","object":"chat.completion.chunk",...,"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

    data: {"id":"chatcmpl-...","object":"chat.completion.chunk",...,"choices":[{"index":0,"delta":{"content":"<tool>"},"finish_reason":null}]}

    ...

    data: {"id":"chatcmpl-...","object":"chat.completion.chunk",...,"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

    data: [DONE]
    ```

=== "Python (openai SDK)"

    ```python
    from openai import OpenAI

    # 認証は未実装なので api_key は任意の文字列でよい
    client = OpenAI(base_url="http://localhost:8080/v1", api_key="unused")

    response = client.chat.completions.create(
        model="gemma4:e2b",
        messages=[{"role": "user", "content": "今の東京の時刻を教えて"}],
    )
    print(response.choices[0].message.content)

    # ストリーミング
    stream = client.chat.completions.create(
        model="gemma4:e2b",
        messages=[{"role": "user", "content": "今の東京の時刻を教えて"}],
        stream=True,
    )
    for chunk in stream:
        print(chunk.choices[0].delta.content or "", end="", flush=True)
    ```

=== "health"

    ```bash
    curl -s localhost:8080/v1/health
    # {"status":"ok"}
    ```

### 会話を続ける

サーバは会話を保持しない (ステートレス)。2ターン目以降は, それまでの`messages`にアシスタントの返答を足して毎回送る。

```bash
curl -s -X POST localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "今の東京の時刻を教えて"},
      {"role": "assistant", "content": "現在の東京の時刻は 15:04 です。"},
      {"role": "user", "content": "ニューヨークは?"}
    ]
  }'
```

## ソースコード

??? abstract "OllamaExample.scala"

    ```scala
    package org.scalafcagent.server.example

    import cats.effect.IO
    import cats.effect.IOApp
    import org.scalafcagent.agent.AgentConfig
    import org.scalafcagent.llm.LLMServerConfig
    import org.scalafcagent.server.CurrentTimeTool
    import org.scalafcagent.server.HttpServer

    object OllamaExample extends IOApp.Simple {

      private def env(name: String, default: String): String =
        sys.env.getOrElse(name, default)

      private def envInt(name: String, default: Int): Int =
        sys.env.get(name).flatMap(_.toIntOption).getOrElse(default)

      private val config: HttpServer.Config =
        HttpServer.Config(
          host = env("HOST", "127.0.0.1"),
          port = envInt("PORT", 8080)
        )

      private val llm: LLMServerConfig =
        LLMServerConfig(
          baseUrl = env("OLLAMA_HOST", "http://localhost:11434"),
          model = env("OLLAMA_MODEL", "qwen3:1.7b")
        )

      def run: IO[Unit] =
        HttpServer
          .resource(llm, AgentConfig(), config, new CurrentTimeTool)
          .use { server =>
            IO.println(
              s"listening on http://${server.host}:${server.port} (model=${llm.model})"
            ) *>
              IO.never // Ctrl-C で Resource が解放され, サーバが止まる
          }
    }
    ```

## 要点

### 自分のToolで立てる

`HttpServer.resource`に渡すToolを差し替えるだけでよい。[型付きToolを作る](../tools/typed_tools.md)のToolを使う場合:

```scala
HttpServer
  .resource(
    llm,
    AgentConfig(systemPrompt = Some("あなたは日本語で答えるアシスタントです。")),
    HttpServer.Config(port = 9000),
    new CurrentTimeTool, new WeatherTool, new AddTool
  )
  .use(_ => IO.never)
```

### Toolの使い方はサーバが差し込む

クライアントが送った`messages`の前に, `AgentConfig.systemPrompt`とToolの使い方をまとめたsystemメッセージをサーバが差し込む。
クライアントの`system`メッセージはその後ろに続く。

### ストリーミングではTool呼び出しも流れる

`stream: true`では, モデルが出した`<tool>...</tool>`もそのまま`delta.content`として流れ, Toolの実行後に次のターンの差分が続く。
クライアントに見せたくない場合は, クライアント側で`<tool>`ブロックを取り除く。

### 制約

- 認証・レート制限は未実装。外部に公開する場合はリバースプロキシなどで保護する
- リクエストの`tools` / `tool_choice`は無視される。有効なToolはサーバに登録したものだけ
- `content`は文字列のみ (マルチモーダルの配列は`400`になる)
