package com.example

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.circe.syntax._
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner

// テスト用のデータ構造（ケースクラス）
case class User(id: Long, name: String, email: String)
case class UserList(users: List[User])

object JsonCodecs {
  implicit val userEncoder: Encoder[User] = deriveEncoder[User]
  implicit val userDecoder: Decoder[User] = deriveDecoder[User]
  implicit val userListEncoder: Encoder[UserList] = deriveEncoder[UserList]
  implicit val userListDecoder: Decoder[UserList] = deriveDecoder[UserList]
}

@RunWith(classOf[JUnitRunner])
class JsonLibrarySpec extends AnyFunSuite with Matchers {
    import JsonCodecs._

    test("Serialize") {
        val user = User(1L, "Takahashi", "test@example.com")
        val expectedJson = """{"id":1,"name":"Takahashi","email":"test@example.com"}"""
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
        user.asJson.noSpaces shouldBe expectedJson
    }
    test("Deserialize2") {
        val jsonString = """{"users":[{"id":1,"name":"Takahashi","email":"test@example.com"}]}"""
        val result = decode[UserList](jsonString)
        result shouldBe Right(UserList(List(User(1L, "Takahashi", "test@example.com"))))
    }
}
