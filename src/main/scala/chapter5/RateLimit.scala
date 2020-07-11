package chapter5

import java.util.concurrent.TimeUnit

import zio.clock.Clock
import zio.duration.Duration
import zio.{ExitCode, UIO, URIO, ZIO, ZManaged}
import zio.duration._

object RateLimit extends zio.App {

  object RateLimitReached extends Exception("rate limit reached")

  final case class Token(value: Int)

  trait TokenBucket {
    def request[R, E, A](token: Token, io: ZIO[R, E, A]): ZIO[R, E, A] // (1)
  }

  object TokenBucket {
    def create(depth: Int, replenishedRate: Duration) // (2)
    : ZManaged[Clock, Nothing, TokenBucket] = for {
      bucket <- zio.Ref.make(Map.empty[Token, Int]).toManaged_ // (3)
      _ <- bucket.update {
        b =>
          b.map { case (t, i) => t -> (if (i < depth) {
            i + 1
          } else {
            i
          })
          }
      }.repeat(zio.Schedule.spaced(replenishedRate)).forkManaged // (4)
      retrySchedule <- zio.Managed.access[Clock](c =>
        zio.Schedule.doWhileEquals(RateLimitReached) && zio.Schedule.spaced(replenishedRate).provide(c) // (5)
      )
    } yield new TokenBucket {
      override def request[R, E, A](token: Token, io: ZIO[R, E, A]): ZIO[R, E, A] = for {
        _ <- bucket.modify {
          b =>
            val current = b.getOrElse(token, depth)
            if (current > 0) {
              UIO.unit -> b.updated(token, current - 1)
            } else {
              ZIO.fail(RateLimitReached) -> b // (6)
            }
        }.flatten.retry(retrySchedule).orDie
        a <- io
      } yield a
    }
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = (for {
    _ <- TokenBucket.create(1, 2.seconds).use { // (7)
      tb =>
        val request = UIO.unit.tap(_ => zio.clock.currentTime(TimeUnit.SECONDS) >>= (s => zio.console.putStrLn(s"done at $s")))
        for {
          _ <- tb.request(Token(1), request)
          _ <- tb.request(Token(1), request)
        } yield ()
    }
  } yield ()).as(ExitCode.success)
}
