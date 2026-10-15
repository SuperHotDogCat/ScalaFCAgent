package org.scalafcagent.server

import cats.effect.IO
import cats.effect.Ref
import org.scalafcagent.agent.Agent

// --------------------
// SessionStore
// --------------------

// HTTPのような複数リクエストを捌く用途で, sessionIdごとにAgent (=1会話) を保持する
trait SessionStore {

  def getOrCreate(sessionId: String): IO[Agent]

  def remove(sessionId: String): IO[Unit]

  def ids: IO[Vector[String]]
}


object SessionStore {

  def inMemory(create: IO[Agent]): IO[SessionStore] =
    Ref.of[IO, Map[String, Agent]](Map.empty).map { ref =>
      new SessionStore {

        def getOrCreate(sessionId: String): IO[Agent] =
          ref.get.map(_.get(sessionId)).flatMap {
            case Some(agent) =>
              IO.pure(agent)

            case None =>
              for {
                agent <- create

                // 競合した場合は先に入った方を優先する
                result <- ref.modify { sessions =>
                  sessions.get(sessionId) match {
                    case Some(existing) =>
                      (sessions, existing)

                    case None =>
                      (sessions.updated(sessionId, agent), agent)
                  }
                }
              } yield result
          }

        def remove(sessionId: String): IO[Unit] =
          ref.update(_ - sessionId)

        def ids: IO[Vector[String]] =
          ref.get.map(_.keys.toVector.sorted)
      }
    }
}
