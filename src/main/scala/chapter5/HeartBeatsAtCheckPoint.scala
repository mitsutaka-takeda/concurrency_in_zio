package chapter5

import zio.{ExitCode, UIO, URIO, ZIO}
import zio.clock.Clock
import zio.duration._
import zio.stream.{ZSink, ZStream}

object HeartBeatsAtCheckPoint extends zio.App {
  lazy val computation: ZStream[Clock, Nothing, Int] = zio.stream.ZStream.range(0, 10) // (1)

  lazy val doWork: ZIO[Any, Nothing, (ZStream[Clock, Nothing, Unit], ZStream[Clock, Nothing, Int])] // (2)
  = for {
    q <- zio.Queue.bounded[Unit](1)
    _ <- q.offer(()) // (3)
  } yield zio.stream.Stream.fromQueue(q) -> computation.mapM(i => q.offer(()).as(i)).ensuringFirst(q.shutdown) // (4)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    (for {
      (heartBeats, results) <- doWork
      _ <- heartBeats.peel(ZSink.head[Unit]).use { // (5)
        case (_, rest) =>
          rest.timeoutError(new Exception("unhealthy"))(2.seconds)  // (6)
            .foreach(_ => zio.console.putStrLn("heart beat"))
            .raceFirst(results.foreach(i => zio.console.putStrLn(i.toString))
            )
      }
    } yield ()).as(ExitCode.success).orDie
}
