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
