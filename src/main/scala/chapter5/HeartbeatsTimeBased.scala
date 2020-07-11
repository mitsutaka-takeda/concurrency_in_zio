package chapter5

import zio._
import zio.clock.Clock
import zio.duration._
import zio.stream.ZStream

object HeartbeatsTimeBased extends zio.App {

  val timeout: Duration = 2.seconds

  val someHeavyComputation: URIO[Clock, Unit] = zio.clock.sleep(10.seconds)

  def computation(interval: Duration): ZStream[Clock, Nothing, Int] = zio.stream.ZStream.range(0, 10).fixed(interval)

  /**
   * 計算を行いながら、`plusInterval`毎にpulseを出力する。
   *
   * TODO: doWorkの実装だとpulseの出力とcomputationが独立している。本当にやりたいことはcomputationが進まなくなったときに
   * pulseの出力を止めたい。
   */
  def doWork(pulseInterval: Duration): ZIO[Any, Nothing, (ZStream[Clock, Nothing, Unit], ZStream[Clock, Nothing, Int])]
  = for {
    p <- zio.Promise.make[Nothing, Unit]
  } yield zio.stream.ZStream.repeat(()).fixed[Any](pulseInterval).haltWhen(p) -> computation(pulseInterval * 2).ensuringFirst(p.completeWith(UIO.unit))

  case object Unhealthy extends Exception("unhealthy")

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = (for {
    (heatBeats, results) <- doWork(1.second)
    ff1 = heatBeats.timeoutError(Unhealthy)(timeout).foreach(_ => zio.console.putStrLn("heart beat"))
    ff2 = results.foreach(i => zio.console.putStrLn(i.toString))
    _ <- ff1.raceFirst(ff2).catchSome{ case Unhealthy => zio.console.putStrLn("timed out")}
  } yield ()).as(ExitCode.success).orDie
}
