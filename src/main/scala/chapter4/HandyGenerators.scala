package chapter4

import java.util.concurrent.TimeUnit

import zio.console.Console
import zio.random.Random
import zio.stream.ZStream
import zio.{ExitCode, URIO, ZIO}

object HandyGenerators extends zio.App {

  def repeat[A](a: A, as: A*): ZStream[Any, Nothing, A] = zio.stream.ZStream.repeat(zio.stream.ZStream.fromIterable(List(a) ++ as)).flatten

  // takeはZStreamのメソッド。
  // def take[R, E, A](s: zio.stream.ZStream[R, E, A], num: Int): ZStream[R, E, A] = s.take(num)

  def repeatFn[A](f: () => A): ZStream[Any, Nothing, A] = zio.stream.ZStream.repeat(f())

  val generate10RandomNumbers: ZStream[Any, Nothing, URIO[Random, Int]] = repeatFn(() => zio.random.nextInt).take(10)

  // IOは副作用を表現する"値"なので、関数(repeatFn)と値(repeat)で別バージョンのrepeatは実装しなくてよい。
  val generate10RandomNumbersWithoutFunction: ZStream[Any, Nothing, URIO[Random, Int]] = zio.stream.ZStream.repeat(zio.random.nextInt).take(10)

  val printRandomNumbers: ZIO[Console with Random, Nothing, ExitCode] = generate10RandomNumbersWithoutFunction.foreach(
    i => i.flatMap(i => zio.console.putStrLn(i.toString))
  ).as(ExitCode.success)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    repeat("I", "am.").take(5).foreach(
      s => zio.console.putStr(s)
    ).as(ExitCode.success).summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }.tap(
      s => zio.console.putStrLn(s"${s._1}").as(s._2)
    ).map(_._2)
}
