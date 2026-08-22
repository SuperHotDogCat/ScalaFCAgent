package com.example

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe.parser._
import io.circe.syntax._
import io.circe.generic.auto._ // 自動でエンコーダ/デコーダを生成
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner

// テスト用のデータ構造（ケースクラス）
case class User(id: Long, name: String, email: String)
case class UserList(users: List[User])

@RunWith(classOf[JUnitRunner])
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
        val expectedJson = """{"users":[{"id":1,"name":"Takahashi","email":"test@example.com"}]}"""
        // .asJson.noSpaces で不要な空白・改行のない文字列に変換
        user.asJson.noSpaces shouldBe expectedJson
    }
    test("Deserialize2") {
        val jsonString = """{"users":[{"id":1,"name":"Takahashi","email":"test@example.com"}]}"""
        val result = decode[UserList](jsonString)
        result shouldBe Right(UserList(List(User(1L, "Takahashi", "test@example.com"))))
    }
}
