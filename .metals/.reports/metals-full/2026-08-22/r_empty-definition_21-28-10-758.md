error id: file://<WORKSPACE>/lib/src/test/scala/org/example/JsonEncodeDecode.scala:shouldBe.
file://<WORKSPACE>/lib/src/test/scala/org/example/JsonEncodeDecode.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -io/circe/parser/shouldBe.
	 -io/circe/parser/shouldBe#
	 -io/circe/parser/shouldBe().
	 -io/circe/syntax/shouldBe.
	 -io/circe/syntax/shouldBe#
	 -io/circe/syntax/shouldBe().
	 -io/circe/generic/auto/shouldBe.
	 -io/circe/generic/auto/shouldBe#
	 -io/circe/generic/auto/shouldBe().
	 -shouldBe.
	 -shouldBe#
	 -shouldBe().
	 -scala/Predef.shouldBe.
	 -scala/Predef.shouldBe#
	 -scala/Predef.shouldBe().
offset: 635
uri: file://<WORKSPACE>/lib/src/test/scala/org/example/JsonEncodeDecode.scala
text:
```scala
package com.example

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe.parser._
import io.circe.syntax._
import io.circe.generic.auto._ // 自動でエンコーダ/デコーダを生成

// テスト用のデータ構造（ケースクラス）
case class User(id: Long, name: String, email: String)
case class UserList(users: List[User])

class JsonLibrarySpec extends AnyFunSuite with Matchers {
    test("Serialize") {
        val user = User(1L, "Takahashi", "test@example.com")
        val expectedJson = """{"id":1,"name":"Takahashi","email":"test@example.com"}"""
        // .asJson.noSpaces で不要な空白・改行のない文字列に変換
        user.asJson.noSpaces @@shouldBe expectedJson
    }
    test("Deserialize") {
        val jsonString = 
    }
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 