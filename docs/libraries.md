# 使用ライブラリ入門

ScalaFCAgentは, Cats Effect / fs2 / circe / Apache Pekko HTTPの上に作られている。
このページでは, それぞれのライブラリを**このリポジトリでは何のために, どの機能だけ使っているか**をまとめる。
ライブラリ全体を覚える必要はなく, ここに出てくる機能が分かればコードもAPIも読める。

| ライブラリ | 役割 | 使っている場所 | 利用者が触るもの |
| --- | --- | --- | --- |
| [Cats Effect](#cats-effect) | 副作用 (通信, Toolの実行, 状態の更新) を`IO`として扱う | `lib`全体, `server` | `IO`, `Resource`, `IOApp` |
| [fs2](#fs2) | ストリーミング出力を`Stream`として扱う | `LLMServer`, `Client`, `server` | `Stream`の`evalMap` / `compile` |
| [circe](#circe) | JSONの読み書き | `Schema`, `ChatFormat`, `LLMServer`, `server` | `Decoder` / `Encoder`の自動導出 |
| [Pekko HTTP](#pekko-http) | HTTPサーバ | `server`モジュールのみ | 通常は触らない |

---

## Cats Effect

### なぜ使っているか

Function Callingの処理は副作用の連続でできている。

- LLMServerへのHTTPリクエスト
- Toolの実行 (時刻の取得, Shellの実行など)
- 会話履歴の更新
- ストリーミング出力の表示
- HTTPクライアントやサーバの起動と終了

これらを`IO`という「まだ実行していない処理の値」として表すと, 実行の順序・エラー処理・後始末を型で表せる。
たとえば`Agent.send`の戻り値`IO[String]`は「実行するとLLMとやりとりして最終出力の`String`を返す処理」を表す。

### `IO` — 実行前の処理

`IO`は作っただけでは何も起きない。`for`でつなげた最後の`IO`を`IOApp`が実行する。

```scala
object Main extends IOApp.Simple {
  def run: IO[Unit] =
    for {
      output <- agent.send("東京の天気は?") // 1. LLMとやりとりする
      _      <- IO.println(output)          // 2. 1の結果を表示する
    } yield ()
}
```

`for`の`<-`は「左の処理を実行して, その結果に名前を付けて次へ進む」と読めばよい。前の行が終わってから次の行が実行されるので, 処理の順序が保証される。

!!! warning "よくある間違い: `IO`を捨てる"
    ```scala
    def run: IO[Unit] = {
      agent.send("hi")          // IOを作っただけで捨てている. 実行されない
      IO.println("done")
    }
    ```
    `IO`は`for`の`<-`か`>>` / `*>`でつながないと実行されない。

このリポジトリで使っている`IO`の機能:

| 機能 | 意味 | 使っている場所 |
| --- | --- | --- |
| `IO.pure(a)` | すでに計算済みの値を`IO`にする | `ToolEngine` (未登録Toolのエラー), 計算だけのTool |
| `IO.blocking { ... }` | スレッドをブロックする処理 (HTTP, ファイル, プロセス) を専用のスレッドで実行する | `OpenAILLMServer.complete`, `CurrentTimeTool` |
| `IO.raiseError(e)` | 失敗した`IO`を作る | `Client` (Tool呼び出し回数の超過) |
| `io.attempt` | 失敗を`Either[Throwable, A]`の値として受け取る | `CurrentTimeTool` (不正なタイムゾーン) |
| `io.handleErrorWith(f)` | 失敗したときに別の`IO`で続ける | `server` (エラーレスポンス) |
| `io.guarantee(fin)` | 成功・失敗・キャンセルのどれでも`fin`を実行する | `server` (SSEのqueueを閉じる) |
| `a >> b`, `a *> b` | `a`のあとに`b`を実行する (`a`の結果は使わない) | 各所 |

### `Resource` — 確保と解放の組

`Resource[IO, A]`は「`A`を確保する処理」と「解放する処理」の組。`use`の中でだけ`A`を使え, `use`を抜けると (例外やキャンセルでも) 必ず解放される。

| `Resource` | 確保 | 解放 |
| --- | --- | --- |
| `OpenAILLMServer.resource` | `HttpClient`を作る | `HttpClient`を閉じる |
| `Agent.create` | 上の`OpenAILLMServer`とAgentを作る | `HttpClient`を閉じる |
| `HttpServer.resource` | `ActorSystem`を作り, ポートにbindする | unbindし, `ActorSystem`を止め, `HttpClient`を閉じる |

```scala
Agent.create(llm, new WeatherTool).use { agent =>
  agent.send("東京の天気は?").flatMap(IO.println)
} // ここでHttpClientが閉じられる
```

内部では`Resource.make(確保)(解放)`で作り, `flatMap`で組み合わせている ([Agent.scala](https://github.com/SuperHotDogCat/ScalaFCAgent/blob/main/lib/src/main/scala/org/Agent.scala)の`create`)。

### `Ref` — 書き換えられる状態

`Ref[IO, A]`は, 複数の`IO`から安全に読み書きできる変数。`Client`は会話 (`Chat`) を`Ref[IO, Chat]`に持ち, ターンごとに更新している。

```scala
for {
  cursor <- Ref.of[IO, Int](0)  // 作る (作るのもIO)
  n      <- cursor.get          // 読む
  _      <- cursor.set(n + 1)   // 書く
} yield ()
```

`Chat`自体はimmutableで, 追加するたびに新しい`Chat`を作る。「今の会話はどれか」だけを`Ref`で持つ, という分担になっている。

### `IOApp` と `unsafe*`

- **`IOApp` / `IOApp.Simple`** — `run`が返した`IO`を実行する`main`。アプリケーションの入口はこれにする
- **`unsafeRunSync()`** — `IO`をその場で実行する。テストでのみ使う (`import cats.effect.unsafe.implicits.global`が必要)
- **`unsafeToFuture()` / `IO.fromFuture`** — Scalaの`Future`との相互変換。`server`でPekko HTTP (`Future`ベース) とつなぐためだけに使っている

---

## fs2

### なぜ使っているか

ストリーミング出力は「値を1つ返す処理」ではなく「差分を何回も返す処理」なので, `IO`だけでは表しにくい。
fs2の`Stream[IO, String]`は「実行すると`String`を0個以上, 順に出す処理」で, `IO`と同じく作っただけでは何も起きない。

`Agent.sendStream` / `LLMServer.stream`が`Stream[IO, String]`を返す。

### 利用者として使う機能

```scala
agent.sendStream("今の東京の時刻は?")
  .evalMap(chunk => IO.print(chunk)) // 差分ごとにIOを実行する
  .compile                           // StreamをIOに変換する準備
  .drain                             // 出力を捨てて IO[Unit] にする
```

| 機能 | 意味 |
| --- | --- |
| `stream.evalMap(f)` | 各要素に`IO`を実行する (表示, 保存など) |
| `stream.compile.drain` | 最後まで実行して`IO[Unit]`にする |
| `stream.compile.toList` | 最後まで実行して全要素を`IO[List[A]]`で受け取る |
| `stream.compile.string` | `Stream[IO, String]`を連結して`IO[String]`にする |

### ライブラリ内部で使っている機能

| 機能 | 意味 | 使っている場所 |
| --- | --- | --- |
| `Stream.eval(io)` | `IO`の結果を1要素のStreamにする | `LLMServer.stream`のデフォルト実装, `Client` |
| `s1 ++ s2` | `s1`を流し終えてから`s2`を流す | `Client` (差分のあとにTool実行と次のターン), `server` (chunk → finish → `[DONE]`) |
| `stream.flatMap(f)` | 要素ごとに次のStreamへつなぐ | 各所 |
| `stream.evalTap(f)` | 要素をそのまま流しつつ`IO`を実行する | `Client` (差分を流しながら`Ref`に溜める) |
| `Stream.bracket(acquire)(release)` | Streamの間だけ資源を持つ | `OpenAILLMServer.stream` (HTTPレスポンスのbodyを閉じる) |
| `Stream.fromBlockingIterator` | ブロックするIteratorをStreamにする | `OpenAILLMServer.stream` (SSEを1行ずつ読む) |
| `Stream.resource(r)` | `Resource`をStreamの間だけ使う | `server` (リクエストごとのAgent) |
| `Stream.raiseError` / `handleErrorWith` | 失敗の発生と回復 | `Client`, `server` |

`Client.converseStream`は, これらを組み合わせて次のように動く。

```scala
deltas                         // LLMServerの差分を流しながら (evalTapで溜める)
  ++ Stream.eval(溜めた出力でToolを実行して会話を更新)
       .flatMap { finished =>
         if (finished) Stream.empty
         else converseStream(remaining - 1) // 次のターンの差分へ続ける
       }
```

---

## circe

### なぜ使っているか

LLMとのやりとりはJSONが中心になる。

- Toolの引数 (`arguments`のJSON文字列 ↔ case class)
- モデル出力の`<tool>`の中身 (`function_name`, `arguments`)
- OpenAI互換APIのリクエストとレスポンス

### 利用者として使う機能

Toolを作るときに, 引数のcase classの`Decoder` / `Encoder`を自動導出する。

```scala
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

case class WeatherArguments(city: String)

object WeatherSchema extends Schema[WeatherArguments] {
  implicit val decoder: Decoder[WeatherArguments] = deriveDecoder
  implicit val encoder: Encoder[WeatherArguments] = deriveEncoder
}
```

`deriveDecoder`は, case classのフィールド名をJSONのキーとして読む`Decoder`を作る。フィールドがない・型が違う場合はdecodeに失敗し, `ToolResult.Error`としてモデルに返る。

### ライブラリ内部で使っている機能

| 機能 | 意味 | 使っている場所 |
| --- | --- | --- |
| `parse(string)` | 文字列を`Json`にする | `ChatFormat`, `LLMServer`, `server` |
| `json.hcursor.downField("a").get[String]("b")` | JSONの中をたどって値を取り出す | `LLMServer` (`choices[0].message.content`) |
| `Json.obj(...)`, `value.asJson` | `Json`を組み立てる | `LLMServer` (リクエスト), `server` (レスポンス) |
| `Decoder.instance` | 手書きの`Decoder` | `ChatFormat` (`function_name` → `functionName`) |

---

## Pekko HTTP

### なぜ使っているか

`server`モジュールでHTTPサーバを立てるためだけに使っている。`lib`は依存しないので, `lib`だけを使う場合は関係ない。
[Apache Pekko](https://pekko.apache.org/)はAkkaのApache-2.0ライセンスのforkで, Pekko HTTPはAkka HTTPとほぼ同じAPIを持つ。

### 使っている機能

| 機能 | 意味 | 使っている場所 |
| --- | --- | --- |
| `ActorSystem` | Pekkoの実行基盤。スレッドプールなどを持つ | `HttpServer.start` (`Resource`で作って止める) |
| `Route` とディレクティブ | URLとメソッドで処理を振り分けるDSL | `Routes.route` |
| `Http(system).newServerAt(host, port).bind(...)` | ポートにbindしてサーバを起動する | `HttpServer.start` |
| `Source.queue` | 外から要素を積めるストリーム | SSEのレスポンスbody |
| `withoutRequestTimeout` | リクエストのタイムアウトを無効にする | LLMの往復が長くなるため |

ルートは次のように読める。

```scala
pathPrefix("v1") {                    // /v1 で始まり
  concat(
    path("health") {                  //   /v1/health で
      get { complete(...) }           //     GET なら返す
    },
    path("chat" / "completions") {    //   /v1/chat/completions で
      post {                          //     POST なら
        entity(as[String]) { body =>  //       bodyを文字列で受け取り
          ...                         //       処理する
        }
      }
    }
  )
}
```

### Cats Effect / fs2 との橋渡し

Pekkoは`Future`とPekko Streamsで動き, `lib`は`IO`とfs2で動くので, `server`は境界で変換している。

| 方向 | 方法 | 場所 |
| --- | --- | --- |
| `IO` → `Future` | `io.unsafeToFuture()`を`onComplete`に渡す | 非ストリーミングの応答 |
| `Future` → `IO` | `IO.fromFuture(IO(future))` | bind / unbind, `ActorSystem.terminate`, `queue.offer` |
| fs2 `Stream` → Pekko `Source` | `Source.queue`がmaterializeされたときにfs2のStreamを走らせ, 各要素を`queue.offer`で積む | ストリーミングの応答 |

既存のPekko HTTPサーバに組み込む場合以外は, `HttpServer.resource`を使えばPekkoを直接触る必要はない。

---

## もっと知りたい場合

- [Cats Effect: Getting Started](https://typelevel.org/cats-effect/docs/getting-started) / [Resource](https://typelevel.org/cats-effect/docs/std/resource) / [Ref](https://typelevel.org/cats-effect/docs/std/ref)
- [fs2: Guide](https://fs2.io/#/guide)
- [circe: Documentation](https://circe.github.io/circe/)
- [Apache Pekko HTTP: Routing DSL](https://pekko.apache.org/docs/pekko-http/current/routing-dsl/index.html)
