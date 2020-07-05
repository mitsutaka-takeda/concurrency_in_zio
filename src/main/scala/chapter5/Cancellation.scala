package chapter5

import zio.blocking.Blocking
import zio.clock.Clock
import zio.{ExitCode, UIO, URIO, ZIO}
import zio.duration._

object Cancellation extends zio.App {

  val reallyLongCalculation: URIO[Clock, Unit] = zio.clock.sleep(10.seconds)

  val case1: ZIO[Any with Clock, Nothing, ExitCode] = reallyLongCalculation.race(UIO.unit.delay(1.second)).as(ExitCode.success)

  val case2: ZIO[Any with Clock, Nothing, ExitCode]
  = UIO.unit.as(longRunningPureComputation()).race(UIO.unit.delay(1.second)).as(ExitCode.success)

  val case3: ZIO[Any with Clock with Blocking, Nothing, ExitCode]
  = UIO.unit.flatMap(_ => zio.blocking.effectBlockingInterrupt(longRunningPureComputation())).race(UIO.unit.delay(1.second)).orDie.as(ExitCode.success)

  val case4: ZIO[Any with Clock, Nothing, ExitCode]
  = UIO.unit.as(longRunningPureComputation()).race(UIO.unit.delay(1.second)).as(ExitCode.success)

  val case5: ZIO[Any with Clock, Nothing, ExitCode]
  = UIO.unit.as(longRunningPureComputation()).disconnect.race(UIO.unit.delay(1.second)).as(ExitCode.success)

  /**
   * 純粋関数はzioのruntimeでは直接interruptできない。
   */
  def longRunningPureComputation(): Unit = {
    println("start")
    Thread.sleep(100000)
    println("end")
  }

  /**
   * raceで2つの処理を並行に実行。raceに負けたほうはinterruptされる。
   *
   * case1のように処理`reallyLongCalculation`がIOモナドで表現されていればinterruptionはすぐ起きる。
   *
   * case2のように純粋関数`longRunningPureComputation`の実行時間が長い場合はinterruptionはすぐに起きない。
   *
   * 純粋関数の実行終了を待たずにfiberをinterruptしたい場合は、純粋関数を`zio.blocking.effectBlockingInterrupt`でラップする(case4)、
   * または処理を`disconnect`することでinterruptionされた処理をバックグランドに移してfiberを終了させることができる(case5)。
   */
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = case5
}
