# Concurrency in ZIO

書籍[Concurrency in Go](https://www.amazon.com/Concurrency-Go-Tools-Techniques-Developers/dp/1491941197)では、Go言語(ChannelやGoroutine)で有効な並行処理のパターンが紹介されています。この記事では紹介されている
並行処理のパターンをZIO with ZStreamで実装していき、副作用をIOとして扱うスタイルの関数型プログラミングでは、どのように並行処理が表現できるか見ていきます。

Go言語版の実装やパターン詳細は書籍をご覧ください。

サンプルコードのレポジトリは[こちら](https://github.com/mitsutaka-takeda/concurrency_in_zio.git)。

# Or-Channel

Or-Channelパターンでは複数の完了（Done）チャンネルを1つのOrチャンネルにまとめます。元のチャンネルのどれか1つが値を送信・完了したとき、Orチャンネルからその値を送信し、まとめられた全てのチャンネルを閉じます。

チャンネルはZStreamで代用します。

```scala
package chapter4

import java.util.concurrent.TimeUnit

import zio.clock.Clock
import zio.duration._
import zio.stream.ZStream
import zio.{ExitCode, URIO}

object OrDone extends zio.App {
  def sig(after: Duration): ZStream[Clock, Nothing, Unit] = zio.stream.ZStream.succeed(()).schedule(
    zio.Schedule.fromDuration(after)
  )

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = for {
    (took, _) <- ZStream.mergeAllUnbounded()( // (1)
      List(2.hours, 5.minutes, 1.second, 1.hour, 1.minute).map(sig): _*
    ).take(1).runDrain // (2)
      .summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }
    _ <- zio.console.putStrLn(s"done after ${took} msec") // => done after 2000 milliseconds
  } yield ExitCode.success
}
```

(1) 複数のStreamを`mergeAllUnbounded`で１つのOrストリームへまとめる。まとめられたストリームは全て並行に処理される。
(2) `take(1)`で合成されたStreamから１つだけ要素が取り出せた時点でストリームを完了する。合成されたストリームが完了すると、子ストリームは全て中断される。

Orストリームの最初の値で処理が完了し、子ストリームが中断されるため指定した完了時間（2時間、5分、1秒、1時間、1分)の中で1番短い1秒に近い時間でプログラムが終了します。

# Pipelines

Pipelineパターンでは処理をステージとよばれる単位に分割し、ステージを連結して処理を行います。ステージは入力を出力に変換する関数です。各ステージは複数要素を並行に処理することも可能です。

`ZStream`では`map`やその変型を使用すると関数をストリーム中の1ステージとして扱うことができます。

加算と乗算のステージからパイプラインを構成します。加算と乗算処理は高負荷な処理であるとみなしてPipelineでのステージ並行化の効果をみます。

```scala
package chapter4

import java.util.concurrent.TimeUnit

import zio.clock.Clock
import zio.console.Console
import zio.duration.{Duration, _}
import zio.stream.ZStream
import zio.{ExitCode, URIO, ZIO}

object Pipeline extends zio.App {
  type UStream[A] = zio.stream.ZStream[zio.ZEnv, Nothing, A]

  val source: ZStream[Console, Nothing, Int] = ZStream.range(1, 4).tap(i => zio.console.putStrLn(s"in range: ${i.toString}"))

  def printAndRecordTime(str: ZStream[zio.ZEnv, Nothing, Int]): ZIO[zio.ZEnv, Nothing, Long] = str.foreach(
    i => zio.console.putStrLn(i.toString)
  ).summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }.map(_._1)

  val delay: Duration = 1000.milliseconds

  def multiply(multiplier: Int): Int => ZIO[Clock, Nothing, Int] = (i: Int) => {
    zio.clock.sleep(delay).as(i * multiplier) // (1)
  }

  def add(additive: Int): Int => ZIO[Clock, Nothing, Int] = (i: Int) =>
    zio.clock.sleep(delay).as(i + additive)


  val nonConcurrency: ZIO[zio.ZEnv, Nothing, Long] = { // (2)
    printAndRecordTime(source.mapM(multiply(2)).mapM(add(1)))
  }

  val concurrency: ZIO[zio.ZEnv, Nothing, Long] = { // (3)
    printAndRecordTime(source.mapMPar(1)(multiply(2)).mapMPar(1)(add(1)))
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = for {
    timingInMilliSec <- nonConcurrency // concurrency
    _ <- zio.console.putStrLn(s"took $timingInMilliSec msec") // nonConcurrency => 7420 msec, concurrency => 5759 msec
  } yield ExitCode.success
}
```

(1) 高負荷な処理を行う代わりにスリープを入れて処理の完了を1秒間遅らせる。
(2) 各ステージ(`add/multiply`)を逐次で処理するパイプライン`nonConcurrency`を定義する。`add/multiply`を`mapM`でステージとしてストリームへ組み込む。
(3) 各ステージ(`add/multiply`)を並行度１で処理するパイプライン`concurrency`を定義する。ステージを並行で処理するため`mapMPar`を使用します。`mapMPar`の第1引数は並行度。

`nonConcurrency`の場合、Pipelineは1要素ずつ処理を行います。`multiply`、`add`ステージともに1秒間かかるので、Pipelineが1要素処理するにはおよそ2秒掛かります。3要素の合計処理時間は約6秒となります。下図参照。

![image01](images/concurrency_in_zio_01.png?raw=true)

`concurrency`の場合は、`multiply`、`add`に並行度１を導入して2要素同時に処理できるパイプラインを構築しました。結果、下図青枠で囲まれているように最初の2要素は並行に処理され合計処理時間が逐次パイプラインよりも短くなります。

![image02](images/concurrency_in_zio_02.png?raw=true)

# Fan-Out, Fan-In

Fan-Out, Fan-Inパターンはパイプラインの一部を並行に処理するパターンです。乱数を生成して素数のみをフィルタするパイプラインを構築します。ある整数が素数か決定する処理は時間がかかるためFan-Out, Fan-Inパターンでそのステージを並行に処理することでパイプラインの処理時間を短縮します。

`ZStream`では前述の`mapMPar`でFan-Out, Fan-Inパターンを実現できます。

```scala
package chapter4

import java.util.concurrent.TimeUnit

import zio.random.Random
import zio.stream.ZStream
import zio.{ExitCode, UIO, URIO}

object FanOutFanIn extends zio.App {

  def isPrime(i: Int): Boolean = !(2 until scala.math.max(2, (i + 1)/2)).exists(v => i % v == 0)

  val intStream: ZStream[Random, Nothing, Int] = zio.stream.Stream.repeat(
    zio.random.nextIntBetween(1, 500000001)
  ).mapM(r => r)

  val nonConcurrency: ZStream[Random, Nothing, Int] = intStream.filter(isPrime) // (1)

  val concurrency: ZStream[Random, Nothing, Int]
  = intStream.mapMParUnordered(8)(i => UIO(i -> isPrime(i))).collect { case (i, isP) if isP => i } // (2)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = concurrency
    .take(10).runDrain // (3)
    .summarized(zio.clock.currentTime(TimeUnit.MILLISECONDS)) { case (start, end) => end - start }.tap {
    case (t, _) => zio.console.putStrLn(t.toString)
  }.run.repeat(zio.Schedule.recurs(10)).as(ExitCode.success)
}
```

(1) 乱数を素数か判定し、素数であればパイプラインの下流へ流す。
(2) 素数判定を並行で行う。最大9要素まで同時に判定する
(3) 素数を10個取得する時間を計測する。

前述の`mapMPar`は、自身のステージを並行に実行して、それ以降のステージへ要素を流す際、入力の順番を保持します。今回使用した`mapMParUnordered`は、Go言語バージョンのチャネルを用いたFan-out,Fan-inパターンと同様に入力の順番を保持しません。この制約の違いで、`mapMParUnordered`を用いた方が`mapMPar`よりも実行時間が短くなります。

![image03](images/concurrency_in_zio_03.png?raw=true)

# Tee-channel

Tee-channelパターンでは１つのチャンネルからの出力を複製して複数のチャンネルへ流します。

`ZStream`では`broadcast`メソッドでTee-channleパターンを実現します。

```scala
package chapter4

import zio.stream.ZStream
import zio.{ExitCode, URIO, ZManaged}

object Tee extends zio.App {
  val stream: ZStream[Any, Nothing, Int] = zio.stream.ZStream.range(1, 10)
  
  val teed: ZManaged[Any, Nothing, List[ZStream[Any, Nothing, Int]]] = stream.broadcast(2, 1) // (1)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    teed.use {
      case out1 :: out2 :: _ => // (2)
        out1.zip(out2).foreach { case (v1, v2) =>
          zio.console.putStrLn(s"out1: $v1, out2: $v2")
        }
    }.as(ExitCode.success)
}
```

(1) `broadcast`メソッドでストリームを指定した数のStreamに分割する。分割後の各ストリームは分割前のStreamが出力した値のコピーを出力する。
(2) 分割後のストリームout1,out2から値を受け取る。

`broadcast`の第2引数（サンプルコードでは１を渡している）は、分割後のストリームの処理にどれだけの遅延が許されるかです。

例えば、v1、v2と連続して値を処理する場合を考えます。v1がout1とout2に送信され、out1でv1の処理が完了しv2の処理を開始したとします。out1はv2を処理していて、out2はv1を処理しているのでout2はout1より1つ分処理が遅延しています。`broadcast`の第2引数で指定した値より遅延が大きくなった場合は、1番遅れている処理を待ちます。

# Bridge-channel

Bridge-channelパターンでは複数のチャンネルを１つのチャンネルにまとめ、チャンネルの消費サイドから複数のチャンネルがあることを隠します。消費サイドでは1つのチャンネルしか見えないため処理が簡潔になります。

`ZStream`では`flatten`で、複数のストリームを1つにまとめることができます。

```scala
package chapter4

import zio.stream.ZStream
import zio.{ExitCode, URIO}

object Bridge extends zio.App {

  val streams: ZStream[Any, Nothing, ZStream[Any, Nothing, Int]] = zio.stream.ZStream.range(0, 10).map(i => zio.stream.ZStream(i))

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    streams.flatten.foreach(i => zio.console.putStrLn(i.toString)).as(ExitCode.success) // (1)
}
```

(1) `flatten`で整数のストリームのストリーム(`ZStream[Any, Nothing, ZStream[Any, Nothing, Int]]`)を整数のストリームへ(`ZStream[Any, Nothing, Int]`)へ変換する。

# Context(デッドライン・キャンセル)

Go言語ではcontextパッケージを使用して処理のデッドラインやキャンセルを行うことができます。

`ZIO`の場合は演算子を利用してデッドラインやキャンセル処理をサポートできます。

```scala
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
```

(1) デッドラインは`raceEither`で表現する。`locale`取得処理に1秒のデッドラインを設定。1秒過ぎると`locale`はキャンセルされる。
(2) `raceEither`でどちらの処理がレースに勝ったかは戻り値で判定する。`locale`が勝った場合は`Left`、デッドラインが勝った場合は`Right`が返る。
(3) `zipPar`で2つの処理を並行に実行する。どちらか片方の処理が失敗するともう一方の処理はキャンセルされる。

# Error Propagation

並行コードではエラーが起きやすく、なぜエラーが起きたのか調査が簡単であることが重要です。「何が起きたのか」、「いつ起きたのか」、「どこで起きたのか」という情報がエラーを調査するために必要です。

`ZIO`では強力なトレース機能でエラーについて必要な情報を詳細に追うことが可能です。

Qiitaをキーワード検索して結果をDBに保存するプログラムを考えます。

```scala
package chapter5

import zio.{ExitCode, URIO, ZIO}

object ErrorPropagation extends zio.App {
  def searchOnQiita(word: String): ZIO[Any, Throwable, String]  = ZIO.succeed("Succeed!")
  def storeResultToDatabase(s: String): ZIO[Any, Throwable, Unit] = ZIO.fail(new Exception("Database failure")) // (1)

  def storeAnswersFromQiita(word: String): ZIO[Any, Throwable, Unit] = for {
    r <- searchOnQiita(word)
    _ <- storeResultToDatabase(r)
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val keyword = "search word"
    storeAnswersFromQiita(keyword)
      .catchAllCause{ c => zio.console.putStrLn(c.prettyPrint).unit} // (2)
      .as(ExitCode.success)
  }
}
```

(1) エラー伝搬の調査のためデータベースへのアクセスは常に失敗するようにする。
(2) エラー情報を標準出力へ出力する。

`ZIO`がエラー情報を保持するために使用している型`Cause`には、エラー発生時のスタックトレースを含めた情報が保存されています。このプログラムを実行すると以下のエラー情報がコンソールへ表示されます。

```text
Fiber failed.
A checked error was not handled.
java.lang.Exception: Database failure <- (1)
	at chapter5.ErrorPropagation$.$anonfun$storeResultToDatabase$1(ErrorPropagation.scala:7) <- (2)
	at zio.ZIO$.$anonfun$fail$1(ZIO.scala:2596)
	at zio.internal.FiberContext.evaluateNow(FiberContext.scala:406)
	at zio.internal.FiberContext.$anonfun$fork$13(FiberContext.scala:753)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)

Fiber:Id(1594760959277,1) was supposed to continue to: <- (3)
  a future continuation at chapter5.ErrorPropagation$.run(ErrorPropagation.scala:17) 
  a future continuation at chapter5.ErrorPropagation$.run(ErrorPropagation.scala:18)

Fiber:Id(1594760959277,1) execution trace: <- (4)
  at chapter5.ErrorPropagation$.storeAnswersFromQiita(ErrorPropagation.scala:11)
  at chapter5.ErrorPropagation$.searchOnQiita(ErrorPropagation.scala:6)

...
```

(1) データベースアクセスが失敗した。（何が起きたのか）
(2) エラーが発生した関数＆コードの行。（どこで起きたのか）
(3) エラーが発生しなかったとしたら何が実行されたか。
(4) エラー発生時のスタックトレース。

エラー発生時のスタックトレースだけではなく、エラーが発生しなかった場合の継続個所が分かるのが特徴です。

# Timeouts & Cancellation

並行・分散システムでは、システムが飽和状態になるのを防ぐ、レスポンスのレイテンシーを抑えるためにタイムアウトが重要になります。またタイムアウトやその他の原因で実行中の処理をキャンセルする必要があります。

`ZIO`を利用した処理のキャンセル方法とキャンセル時に注意しなければいけないことを見ていきます。

`ZIO`や関数型IOライブラリでは、IO型の値としてプログラムを組み立て、そのIO型の値を実行環境が実行します。IO型で表現されている処理は実行環境の制御下にあるため、処理のキャンセルは容易に行うことができます。しかしIO型で表現されていない処理はキャンセルすることができません。そのような場合は処理をバックグランドに持っていくことでキャンセルとして扱います。

```scala
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
```
(1) IO型で表現された処理。実行環境がキャンセル可能。
(2) IO型で表現されていない処理。実行環境がキャンセル不可能。
(3) 処理がIO型で表現されている2つの処理を競争させる。
(4) 非IO型の処理`longRunningPureComputation`とIO型の処理を競争させる。
(5) キャンセル時に非IO型の処理をバックグランド実行するため`effectBlockingInterrupt`を使用。
(6) `disconnect`演算子を使用しても(5)と同じ動作になる。

case1 ~ 4では10秒かかる処理と1秒かかる処理を`race`演算子で競争させています。`race`演算子は片方の処理が終わった時点でもう片方の処理をキャンセルしようとします。

case1では10秒かかる処理`reallyLongCalculation`はIO型で表現されているため1秒後すぐにキャンセルされcase1の処理は約1秒で終了します。

case2では10秒かかる処理`longRunningPureComputation`が非IO型のため、ZIOの実行環境は処理をキャンセルすることができません。そのためcase2は10秒後に終了します。

case3&4ではキャンセル時に非IO型の処理をバックグランドへ移動させます。case1と同様にcase3&4は1秒後に完了します。

# HeartBeats

HeartBeatsパターンでは長期間実行される処理から定期的に信号を受け取ることで処理が進行中であることを確認するパターンです。信号が受け取れなくなった時点で処理の中断や再起動を行うことができます。HeartBeatsでは間隔を指定して、その間隔で信号を送信するタイプと処理を細かな単位に分割してその単位の開始時に信号を送信するタイプの2種類があります。

`ZStream`を使用するとHeartBeatsパターンを実現することができます。2つ目の作業単位の開始時に信号を送るパターンを実装します。

```scala
package chapter5

import zio.{ExitCode, UIO, URIO, ZIO}
import zio.clock.Clock
import zio.duration._
import zio.stream.{ZSink, ZStream}

object HeartBeatsAtCheckPoint extends zio.App {
  lazy val computation: ZStream[Clock, Nothing, Int] = zio.stream.ZStream.range(0, 10) // (1)

  lazy val doWork: ZIO[Any, Nothing, (ZStream[Clock, Nothing, Unit], ZStream[Clock, Nothing, Int])] // (2)
  = for {
    q <- zio.Queue.bounded[Unit](1)
    _ <- q.offer(()) // (3)
  } yield zio.stream.Stream.fromQueue(q) -> computation.mapM(i => q.offer(()).as(i)).ensuringFirst(q.shutdown) // (4)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    (for {
      (heartBeats, results) <- doWork
      _ <- heartBeats.peel(ZSink.head[Unit]).use { // (5)
        case (_, rest) =>
          rest.timeoutError(new Exception("unhealthy"))(2.seconds)  // (6)
            .foreach(_ => zio.console.putStrLn("heart beat"))
            .raceFirst(results.foreach(i => zio.console.putStrLn(i.toString))
            )
      }
    } yield ()).as(ExitCode.success).orDie
}
```
(1) 処理を作業単位に分割。中間の結果をストリームで出力。
(2) HeartBeatsはStreamで表現する。1つ目のストリームはHeartBeats用、2つ目のストリームは処理の結果用。
(3) 信号はキューで送信する。処理開始前の信号を送信。
(4) 中間結果を送信する前にキューで信号を送信。計算処理の終了時にHeartBeats用のキューを閉じる。
(5) (2)で送信された処理前の信号を待ってから、計算処理のストリームを開始する。
(6) HeartBeatsが2秒以上受信できない場合はストリームを中断する。

# Replicated Requests

レイテンシーを改善するため重複してリクエストを発行し最も早く返ってきた結果を利用するのがReplicated Requestsパターンです。

`ZIO`の`race`演算子とその変型を使うとリクエストを複数競争させることが可能です。

```scala
package chapter5

import zio.clock.Clock
import zio.console.Console
import zio.{ExitCode, UIO, URIO, ZIO}
import zio.duration._
import zio.random.Random

object ReplicatedRequests extends zio.App {
  val simulatedLoadTime: ZIO[Random, Nothing, Duration] = zio.random.nextIntBetween(1, 6).map(_.seconds) // (1)

  def doWork(id: Int): ZIO[Random with Console with Clock, Nothing, Unit] = (for { // (2)
    sl <- simulatedLoadTime
    _ <- zio.clock.sleep(sl)
  } yield ()).catchAllCause(_ => UIO.unit)
    .summarized(zio.clock.nanoTime) { case (start, end) => (end - start).nanoseconds }
    .flatMap { case (t, _) =>
      zio.console.putStrLn(s"${id.toString} took ${t.toString}")
    }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    ZIO.raceAll(doWork(0), (1 to 10).map(i => doWork(i))).as(ExitCode.success) // (3)
}
```
(1) サービスのレスポンス時間のばらつきを乱数でシミュレートする。
(2) １つのリクエスト処理。
(3) 10個のリクエストを発行し、最初の結果を使用する。

# Rate Limiting

Rate Limitingパターンでは単位時間当たりのリソースへのアクセス回数を制御します。APIの呼び出し回数などの制限を回避するのに有効です。

書籍で紹介されているトークン・バケット方式のRate Limitを実装します。トークン・バケット方式では、トークン毎にアクセス回数を制御します。トークン毎にカウンターを持ちます。トークンを指定してリクエストを行うと、そのトークンに対応したカウンターをデクリメントします。カウンターが0になるとそれ以降はアクセスできません。カウンターは指定した間隔`replenishedRate`でインクリメントされ、指定した値`depth`に達するとそれ以上はインクリメントされません。`depth`と`replenishedRate`を制御することで単位時間辺りのリソースへのアクセス回数を制御します。

`ZIO`では`Ref`構造体でカウンターを実装します。

```scala
package chapter5

import java.util.concurrent.TimeUnit

import zio.clock.Clock
import zio.duration.Duration
import zio.{ExitCode, UIO, URIO, ZIO, ZManaged}
import zio.duration._

object RateLimit extends zio.App {

  object RateLimitReached extends Exception("rate limit reached")

  final case class Token(value: Int)

  trait TokenBucket {
    def request[R, E, A](token: Token, io: ZIO[R, E, A]): ZIO[R, E, A] // (1)
  }

  object TokenBucket {
    def create(depth: Int, replenishedRate: Duration) // (2)
    : ZManaged[Clock, Nothing, TokenBucket] = for {
      bucket <- zio.Ref.make(Map.empty[Token, Int]).toManaged_ // (3)
      _ <- bucket.update {
        b =>
          b.map { case (t, i) => t -> (if (i < depth) {
            i + 1
          } else {
            i
          })
          }
      }.repeat(zio.Schedule.spaced(replenishedRate)).forkManaged // (4)
      retrySchedule <- zio.Managed.access[Clock](c =>
        zio.Schedule.doWhileEquals(RateLimitReached) && zio.Schedule.spaced(replenishedRate).provide(c) // (5)
      )
    } yield new TokenBucket {
      override def request[R, E, A](token: Token, io: ZIO[R, E, A]): ZIO[R, E, A] = for {
        _ <- bucket.modify {
          b =>
            val current = b.getOrElse(token, depth)
            if (current > 0) {
              UIO.unit -> b.updated(token, current - 1)
            } else {
              ZIO.fail(RateLimitReached) -> b // (6)
            }
        }.flatten.retry(retrySchedule).orDie
        a <- io
      } yield a
    }
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = (for {
    _ <- TokenBucket.create(1, 2.seconds).use { // (7)
      tb =>
        val request = UIO.unit.tap(_ => zio.clock.currentTime(TimeUnit.SECONDS) >>= (s => zio.console.putStrLn(s"done at $s")))
        for {
          _ <- tb.request(Token(1), request)
          _ <- tb.request(Token(1), request)
        } yield ()
    }
  } yield ()).as(ExitCode.success)
}
```
(1) トークンバケット方式による制御のインターフェイス。指定したトークンで処理`io`を実行する。
(2) トークンバケット方式の実装。`depth`と`replenishedRate`を指定してアルゴリズムを実装する。
(3) `Ref`を利用して安全に並行アクセス可能なカウンターを実現する。
(4) `replenishedRate`間隔でカウンターをインクリメントする。
(5) アクセス上限`RateLimitReached`に達したときのリトライ・ポリシー。`replenishedRate`だけリクエストを送らせてリトライする。
(6) バケットのカウンターが0に達したときはエラー`RateLimitReached`にする。
(7) 2秒間に1回までのアクセス制限を行う。

# 最後に

並行プログラミングのパターンをZIO/ZStreamで実装していく基本的流れは以下の通りです。

- 処理の基本単位（単発のリクエストなど）をIO型の値で表現する。
- `race`などの演算子を用いて、基本単位の処理を組み合わせる、または処理の動作を変更する。

このようにプログラミングすることで、プログラム全体の構成を変更することなく演算子の追加・変更によって、プログラムの動作を変えることができます。Fan-In,Fan-Outパターンで`mapMPar`と`mapMParUnordered`の違いを説明しました。１つの演算子を切り替えるだけで、出力の順序補償のあるなしを切り替えることができます。合成可能な演算子を組み合わせて多彩なアルゴリズムを表現できるのが、関数型IO/Streamライブラリの特徴です。

Concurrency in Goは並行プログラミングを行う上で有効なパターンが紹介されている良書です。Go言語以外でプログラミングするうえでも役に立つパターンが紹介されており、ZIO/ZStreamでどのようにパターンを表現できるか考えるのはZIO/ZStreamがどのように振舞うのか確認する良い機会になりました。皆さんも普段使用されている言語で、どのように上記のパターンを実現できるか考えてみてください。