package chapter4

import java.util.concurrent.TimeUnit

import zio.duration.Duration
import zio.stream.ZStream
import zio.{ExitCode, UIO, URIO, ZIO}
import zio.duration._

object Pipeline extends zio.App {
  type UStream[A] = zio.stream.ZStream[zio.ZEnv, Nothing, A]

  val delay: Duration = 1000.milliseconds

  val baseline: ZIO[zio.ZEnv, Nothing, Long] = {
    def multiply(ints: UStream[Int], multiplier: Int): UStream[Int] = ints.mapM(i => {
      UIO(i * multiplier)
    })

    def add(ints: UStream[Int], additive: Int): UStream[Int] = ints.mapM(i =>
      UIO(i + additive)
    )

    add(multiply(ZStream.range(1, 4).tap(i => zio.console.putStrLn(s"in range: ${i.toString}")), 2), 1).foreach(
      i => zio.console.putStrLn(i.toString)
    ).summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }.map(_._1)
  }

  val nonConcurrency: ZIO[zio.ZEnv, Nothing, Long] = {
    def multiply(ints: UStream[Int], multiplier: Int): UStream[Int] = ints.mapM(i => {
      zio.clock.sleep(delay).as(i * multiplier)
    })

    def add(ints: UStream[Int], additive: Int): UStream[Int] = ints.mapM(i =>
      zio.clock.sleep(delay).as(i + additive)
    )

    add(multiply(ZStream.range(1, 4).tap(i => zio.console.putStrLn(s"in range: ${i.toString}")), 2), 1).foreach(
      i => zio.console.putStrLn(i.toString)
    ).summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }.map(_._1)
  }

  val concurrency: ZIO[zio.ZEnv, Nothing, Long] = {
    def multiply(ints: UStream[Int], multiplier: Int): UStream[Int] = ints.mapMPar(1)(i => {
      zio.clock.sleep(delay).as(i * multiplier)
    })

    def add(ints: UStream[Int], additive: Int): UStream[Int] = ints.mapMPar(1)(i =>
      zio.clock.sleep(delay).as(i + additive)
    )

    add(multiply(ZStream.range(1, 4).tap(i => zio.console.putStrLn(s"in range: ${i.toString}")), 2), 1).foreach(
      i => zio.console.putStrLn(i.toString)
    ).summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }.map(_._1)
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = for {
    timingInMilliSec <- baseline
    // nonConcurrency
    // concurrency
    _ <- zio.console.putStrLn(s"took $timingInMilliSec msec")
  } yield ExitCode.success
}
