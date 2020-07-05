package chapter5

import zio.clock.Clock
import zio.duration._
import zio._

object Heartbeats extends zio.App {

  val someHeavyComputation: URIO[Clock, Unit] = zio.clock.sleep(10.seconds)

  /**
   * Queueを使用してHeartBeatで処理中であることを伝える。
   *
   */
  def doWork(pulse: zio.Queue[Unit], pulseInterval: Duration): ZIO[Clock, Nothing, Unit] =
    ZManaged.make(UIO(pulse))(_.shutdown).use { // (1) 例外など予期しない状況でQueueがリークしないようにManagedを使用する。
      p =>
        // (2) 指定したスケジュールでHeartBeatを送信する。
        // HeartBeat送信処理は実際のタスク(someHeavyComputation)と並列で行う。タスクが成功、または、失敗した時点でHeartBeatも終了する。
        p.offer(()).repeat(zio.Schedule.fixed(pulseInterval)).map(Left(_)).raceFirst(
          someHeavyComputation.map(Right(_))
        ).flatMap(e =>
          ZIO.fromEither(e).orElseFail(new Exception("heat beat should never stops before the task."))
        ).orDie
    }

  val timeout: ZIO[Clock, Nothing, Unit] = ZIO.unit.delay(2.seconds)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = (for {
    queue <- zio.Queue.bounded[Unit](1)
    f <- doWork(queue, 3.second).fork
    // (3) HeartBeatを受け取る。終了時にqueueが閉じられるとファイバーがinterruptされる。
    v <- queue.take.tap(_ => zio.console.putStrLn("heart beat"))
      .raceEither(timeout *> zio.console.putStrLn("worker is unhealthy"))
      .doWhile(_.isLeft) // (4) Left is healthy and Right is unhealthy
      .race(queue.awaitShutdown.map(Left(_)))
      .catchSomeCause { case c if c.interruptedOnly => UIO.unit.map(Right(_)) }
    _ <- v.fold(_ => f.join, _ => f.interrupt)
  } yield ()).as(ExitCode.success)
}
