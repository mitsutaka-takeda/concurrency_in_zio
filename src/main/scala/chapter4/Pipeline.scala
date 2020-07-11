package chapter4

import java.util.concurrent.TimeUnit

import zio.clock.Clock
import zio.console.Console
import zio.duration.{Duration, _}
import zio.stream.ZStream
import zio.{ExitCode, URIO, ZIO}

object Pipeline extends zio.App {
  type UStream[A] = zio.stream.ZStream[zio.ZEnv, Nothing, A]

  val source: ZStream[Console, Nothing, Int] = ZStream.range(1, 4).tap(i => zio.console.putStrLn(s"in range: ${i.toString}"))

  def printAndRecordTime(str: ZStream[zio.ZEnv, Nothing, Int]): ZIO[zio.ZEnv, Nothing, Long] = str.foreach(
    i => zio.console.putStrLn(i.toString)
  ).summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }.map(_._1)

  val delay: Duration = 1000.milliseconds

  def multiply(multiplier: Int): Int => ZIO[Clock, Nothing, Int] = (i: Int) => {
    zio.clock.sleep(delay).as(i * multiplier) // (1)
  }

  def add(additive: Int): Int => ZIO[Clock, Nothing, Int] = (i: Int) =>
    zio.clock.sleep(delay).as(i + additive) // (1)


  val nonConcurrency: ZIO[zio.ZEnv, Nothing, Long] = { // (2)
    printAndRecordTime(source.mapM(multiply(2)).mapM(add(1)))
  }

  val concurrency: ZIO[zio.ZEnv, Nothing, Long] = { // (3)
    printAndRecordTime(source.mapMPar(1)(multiply(2)).mapMPar(1)(add(1)))
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = for {
    timingInMilliSec <- concurrency
      // nonConcurrency
    // concurrency
    _ <- zio.console.putStrLn(s"took $timingInMilliSec msec") // nonConcurrency => 7420 msec, concurrency => 5759 msec
  } yield ExitCode.success
}
