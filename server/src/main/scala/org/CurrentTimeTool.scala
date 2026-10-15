package org.scalafcagent.server

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import cats.effect.IO
import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import org.scalafcagent.tool.Schema
import org.scalafcagent.tool.ToolResult
import org.scalafcagent.tool.TypedTool

// --------------------
// 例のTool
// --------------------

case class CurrentTimeArguments(
  timezone: String
)

object CurrentTimeSchema extends Schema[CurrentTimeArguments] {

  implicit val decoder: Decoder[CurrentTimeArguments] =
    deriveDecoder[CurrentTimeArguments]

  implicit val encoder: Encoder[CurrentTimeArguments] =
    deriveEncoder[CurrentTimeArguments]
}

// 指定タイムゾーンの現在時刻を返すTool
class CurrentTimeTool extends TypedTool[CurrentTimeArguments] {

  override val name: String =
    "current_time"

  override val description: String =
    "Get the current time in the given IANA timezone"

  override val argumentJSONSchema: String =
    """{"type":"object","properties":{"timezone":{"type":"string","description":"IANA timezone, e.g. Asia/Tokyo"}},"required":["timezone"]}"""

  override val schema: Schema[CurrentTimeArguments] =
    CurrentTimeSchema

  override def run(args: CurrentTimeArguments): IO[ToolResult] =
    IO.blocking(ZoneId.of(args.timezone)).attempt.map {

      case Right(zone) =>
        ToolResult.Success(
          ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        )

      case Left(_) =>
        ToolResult.Error(s"Unknown timezone: ${args.timezone}")
    }
}
