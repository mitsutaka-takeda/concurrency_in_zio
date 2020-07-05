package chapter4

import zio.stream.ZStream
import zio.{ExitCode, URIO}

object Bridge extends zio.App {

  val streams: ZStream[Any, Nothing, ZStream[Any, Nothing, Int]] = zio.stream.ZStream.range(0, 10).map(i => zio.stream.ZStream(i))

  /**
   * StreamのStreamはflattenで1つのStreamにまとめることができる。
   */
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    streams.flatten.foreach(i => zio.console.putStrLn(i.toString)).as(ExitCode.success)
}
