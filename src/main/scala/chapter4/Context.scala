package chapter4

import zio.clock.Clock
import zio.console.Console
import zio.{ExitCode, UIO, URIO, ZIO}
import zio.duration._

/**
 * Deadlineやキャンセル情報を与える。
 */
object Context extends zio.App {

  lazy val locale: ZIO[Clock, Nothing, String] = zio.clock.sleep(1.minute).as("EN/US")

  /**
   * Deadlineは他の副作用とのraceで表現できる。
   */
  lazy val genGreeting: ZIO[Clock, Exception, String] = locale.raceEither(UIO.unit.delay(1.seconds)).flatMap {
    case Left("EN/US") => UIO("hello")
    case Left(s) => ZIO.fail(new Exception(s"unsupported locale: $s"))
    case Right(_) => ZIO.fail(new Exception("timeout"))
  }

  lazy val genFarewell: ZIO[Clock, Exception, String] = locale.flatMap {
    case "EN/US" => UIO("goodbye")
    case _ => ZIO.fail(new Exception("unsupported locale"))
  }

  lazy val printGreeting: ZIO[Console with Clock, Exception, Unit] = genGreeting.flatMap {
    s => zio.console.putStrLn(s"$s world!")
  }

  lazy val printFarewell: ZIO[Console with Clock, Exception, Unit] = genFarewell.flatMap {
    s => zio.console.putStrLn(s"$s world!")
  }

  /**
   * zipParで２つの副作用を並行に実行する。
   *
   * zipParで接続された2つの副作用は片方が失敗した場合、もう一方は直ちに割り込みされるので、Contextによる調整は不要。
   */
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    printGreeting.zipPar(printFarewell).orDie.as(ExitCode.success)
}
