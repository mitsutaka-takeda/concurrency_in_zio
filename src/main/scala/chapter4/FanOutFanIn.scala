package chapter4

import java.util.concurrent.TimeUnit

import zio.random.Random
import zio.stream.ZStream
import zio.{ExitCode, UIO, URIO}

object FanOutFanIn extends zio.App {

  def isPrime(i: Int): Boolean = !(2 until scala.math.max(2, (i + 1)/2)).exists(v => i % v == 0)

  val intStream: ZStream[Random, Nothing, Int] = zio.stream.Stream.repeat(
    zio.random.nextIntBetween(1, 500000001)
  ).mapM(r => r)

  val nonConcurrency: ZStream[Random, Nothing, Int] = intStream.filter(isPrime) // (1)

  val concurrency: ZStream[Random, Nothing, Int]
  = intStream.mapMPar(8)(i => UIO(i -> isPrime(i))).collect { case (i, isP) if isP => i } // (2)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = concurrency
    .take(10).runDrain // (3)
    .summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }.tap {
    case (t, _) => zio.console.putStrLn(t.toString)
  }.run.repeat(zio.Schedule.recurs(10)).as(ExitCode.success)
}
