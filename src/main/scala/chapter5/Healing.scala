package chapter5

import zio.clock.Clock
import zio.duration._
import zio.stream.ZStream
import zio.{ExitCode, Task, UIO, URIO, ZIO}

object Healing extends zio.App {
  def ward(pulseInterval: Duration): ZIO[Any, Nothing, (ZStream[Clock, Nothing, Unit], ZStream[Any, Throwable, Unit])] =
    for {
      p <- zio.Promise.make[Nothing, Unit]
    } yield zio.stream.ZStream.repeat(()).fixed[Any](3.seconds).haltWhen(p) -> zio.stream.ZStream.fromEffect(
      Task(java.lang.Thread.sleep(10000))
    ).ensuring(p.succeed(()))

  case object Unhealthy extends Exception("unhealthy")

  def steward
  (timeout: Duration,
   ward: Duration => ZIO[Any, Nothing, (ZStream[Clock, Nothing, Unit], ZStream[Any, Throwable, Unit])]): ZIO[Clock, Throwable, Option[Unit]]
  = (for {
    (heartBeat, result) <- ward((timeout.toMillis / 2).milliseconds)
    r <- heartBeat.timeoutError(Unhealthy)(timeout).runDrain.as(None).raceFirst(result.runLast)
  } yield r).retry(zio.Schedule.doWhileM(
    e => UIO(println("healing")).as(e == Unhealthy)))

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = (for {
    result <- steward(2.seconds, ward)
  } yield result).orDie.as(ExitCode.success)
}
