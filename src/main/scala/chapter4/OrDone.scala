package chapter4

import zio.clock.Clock
import zio.stream.ZStream
import zio.{ExitCode, Promise, UIO, URIO}
import zio.duration._

/**
 * StreamのキャンセルはPromiseとhaltWhenで行うことができる。
 */
object OrDone extends zio.App {
  // 毎秒１を出力するストリーム。
  val stream: ZStream[Clock, Nothing, Int] = zio.stream.ZStream.tick(1.second).map(_ => 1)

  // ストリーム完了用のプロミス。
  val done: UIO[Promise[Nothing, Unit]] = zio.Promise.make[Nothing, Unit]

  /**
   * 標準入力から入力があるまで、毎秒1を出力するプログラム。
   */
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = for {
    p <- done
    f <- stream.haltWhen(p).foreach(i => zio.console.putStrLn(i.toString)).fork // Streamの処理は別ファイバーで。
    _ <- zio.console.getStrLn.flatMap(_ => p.complete(UIO.unit)).orDie // 入力が有った時点でPromiseをトリガー。
    _ <- f.join
  } yield ExitCode.success
}
