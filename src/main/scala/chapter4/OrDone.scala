package chapter4

import java.util.concurrent.TimeUnit

import zio.clock.Clock
import zio.duration._
import zio.stream.ZStream
import zio.{ExitCode, URIO}

object OrDone extends zio.App {
  def sig(after: Duration): ZStream[Clock, Nothing, Unit] = zio.stream.ZStream.succeed(()).schedule(
    zio.Schedule.fromDuration(after)
  )

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = for {
    (took, _) <- ZStream.mergeAllUnbounded()( // (1)
      List(2.hours, 5.minutes, 1.second, 1.hour, 1.minute).map(sig): _*
    ).take(1).runDrain // (2)
      .summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }
    _ <- zio.console.putStrLn(s"done after ${took} msec") // => done after 2000 milliseconds
  } yield ExitCode.success
}
