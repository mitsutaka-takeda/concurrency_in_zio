package chapter4

import zio.clock.Clock
import zio.console.Console
import zio.{ExitCode, UIO, URIO, ZIO}
import zio.duration._

object Context extends zio.App {

  lazy val locale: ZIO[Clock, Nothing, String] = zio.clock.sleep(1.minute).as("EN/US")

  lazy val genGreeting: ZIO[Clock, Exception, String] = locale.raceEither(UIO.unit.delay(1.seconds)).flatMap { // (1)
    case Left("EN/US") => UIO("hello")
    case Left(s) => ZIO.fail(new Exception(s"unsupported locale: $s"))
    case Right(_) => ZIO.fail(new Exception("timeout")) // (2)
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

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    printGreeting.zipPar(printFarewell).orDie.as(ExitCode.success) // (3)
}
