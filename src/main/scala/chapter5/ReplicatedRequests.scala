package chapter5

import zio.clock.Clock
import zio.console.Console
import zio.{ExitCode, UIO, URIO, ZIO}
import zio.duration._
import zio.random.Random

object ReplicatedRequests extends zio.App {
  val simulatedLoadTime: ZIO[Random, Nothing, Duration] = zio.random.nextIntBetween(1, 6).map(_.seconds) // (1)

  def doWork(id: Int): ZIO[Random with Console with Clock, Nothing, Unit] = (for { // (2)
    sl <- simulatedLoadTime
    _ <- zio.clock.sleep(sl)
  } yield ()).catchAllCause(_ => UIO.unit)
    .summarized(zio.clock.nanoTime) { case (start, end) => (end - start).nanoseconds }
    .flatMap { case (t, _) =>
      zio.console.putStrLn(s"${id.toString} took ${t.toString}")
    }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    ZIO.raceAll(doWork(0), (1 to 10).map(i => doWork(i))).as(ExitCode.success) // (3)
}
