package chapter5

import zio.blocking.Blocking
import zio.clock.Clock
import zio.{ExitCode, UIO, URIO, ZIO}
import zio.duration._

object Cancellation extends zio.App {

  val reallyLongCalculation: URIO[Clock, Unit] = zio.clock.sleep(10.seconds) // (1)

  def longRunningPureComputation(): Unit = { // (2)
    println("start")
    Thread.sleep(100000)
    println("end")
  }

  val case1: ZIO[Any with Clock, Nothing, ExitCode]
  = reallyLongCalculation.race(UIO.unit.delay(1.second)).as(ExitCode.success) // (3)

  val case2: ZIO[Any with Clock, Nothing, ExitCode]
  = UIO.unit.as(longRunningPureComputation()).race(UIO.unit.delay(1.second)).as(ExitCode.success) // (4)

  val case3: ZIO[Any with Clock with Blocking, Nothing, ExitCode]
  = UIO.unit.flatMap(_ => zio.blocking.effectBlockingInterrupt(longRunningPureComputation())) // (5)
    .race(UIO.unit.delay(1.second)).orDie.as(ExitCode.success)

  val case4: ZIO[Any with Clock, Nothing, ExitCode]
  = UIO.unit.as(longRunningPureComputation()).disconnect // (6)
    .race(UIO.unit.delay(1.second)).as(ExitCode.success)


  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = case4
}
