error id: file://<WORKSPACE>/lib/src/test/scala/org/example/JsonEncodeDecode.scala:noSpaces.
file://<WORKSPACE>/lib/src/test/scala/org/example/JsonEncodeDecode.scala
empty definition using pc, found symbol in pc: noSpaces.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -io/circe/parser/user/asJson/noSpaces.
	 -io/circe/parser/user/asJson/noSpaces#
	 -io/circe/parser/user/asJson/noSpaces().
	 -io/circe/syntax/user/asJson/noSpaces.
	 -io/circe/syntax/user/asJson/noSpaces#
	 -io/circe/syntax/user/asJson/noSpaces().
	 -io/circe/generic/auto/user/asJson/noSpaces.
	 -io/circe/generic/auto/user/asJson/noSpaces#
	 -io/circe/generic/auto/user/asJson/noSpaces().
	 -user/asJson/noSpaces.
	 -user/asJson/noSpaces#
	 -user/asJson/noSpaces().
	 -scala/Predef.user.asJson.noSpaces.
	 -scala/Predef.user.asJson.noSpaces#
	 -scala/Predef.user.asJson.noSpaces().
offset: 1164
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
        user.asJson.noSpaces shouldBe expectedJson
    }
    test("Deserialize") {
        val jsonString = """{"id":1,"name":"Takahashi","email":"test@example.com"}"""
        val result = decode[User](jsonString)
        result shouldBe Right(User(1L, "Takahashi", "test@example.com"))
    }
    test("Serialize2") {
        val user = UserList(List(User(1L, "Takahashi", "test@example.com")))
        val expectedJson = """[{"id":1,"name":"Takahashi","email":"test@example.com"}]"""
        // .asJson.noSpaces で不要な空白・改行のない文字列に変換
        user.asJson.noSpa@@ces shouldBe expectedJson
    }
        test("Deserialize2") {
        val user = UserList(List(User(1L, "Takahashi", "test@example.com")))
        val expectedJson = """[{"id":1,"name":"Takahashi","email":"test@example.com"}]"""
        // .asJson.noSpaces で不要な空白・改行のない文字列に変換
        user.asJson.noSpaces shouldBe expectedJson
    }
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: noSpaces.