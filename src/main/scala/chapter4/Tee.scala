package chapter4

import zio.stream.ZStream
import zio.{ExitCode, URIO, ZManaged}

object Tee extends zio.App {
  val stream: ZStream[Any, Nothing, Int] = zio.stream.ZStream.range(1, 10)

  /**
   * broadcastメソッドでStreamを指定した数のStreamに分割できる。分割後の各ストリームは分割前のStreamが出力した値の
   * コピーを出力する。
   */
  val teed: ZManaged[Any, Nothing, List[ZStream[Any, Nothing, Int]]] = stream.broadcast(2, 1)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    teed.use {
      case out1 :: out2 :: _ =>
        out1.zip(out2).foreach { case (v1, v2) =>
          zio.console.putStrLn(s"out1: $v1, out2: $v2")
        }
    }.as(ExitCode.success)
}
