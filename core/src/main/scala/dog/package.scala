import scalaz._

package object dog {

  type TestCase[A] = Kleisli[TestResult, Endo[Param], A]

  object TestCase {

    def apply[A](result: => TestResult[A]): TestCase[A] =
      Kleisli.kleisli((paramEndo: Endo[Param]) => try {
        // TODO: cancellation
        // val p = paramEndo(Param.default)
        lazy val r = result
        r
      } catch {
        case e: Throwable => TestResult.error[A](IList.single(e), IList.empty)
      })

    def ok[A](value: A): TestCase[A] = apply(TestResult(value))

    def delay[A](test: => TestCase[A]): TestCase[A] = test

    def fixture(f: () => Unit): TestCase[Unit] = {
      f()
      ok(())
    }
  }

  implicit def toTestCase[A](result: => AssertionResult[A]): TestCase[A] =
    TestCase(AssertionResult.toTestResult(result))

  implicit class TestCaseSyntax[A](self: TestCase[A]) {

    def +>(result: AssertionResult[A]): TestCase[A] = self.mapK(_ +> result)
  }

  implicit class TestCaseByNameSyntax[A](self: => TestCase[A]) {

    def skip(reason: String): TestCase[A] = TestCase(
      Done(NonEmptyList.nel(-\/(NotPassedCause.skip(reason)), IList.empty))
    )
  }

  type AssertionResult[A] = NotPassedCause \/ A

  type AssertionNel[A] = NonEmptyList[AssertionResult[A]]

  object AssertionResult {

    def onlyNotPassed[A](xs: AssertionNel[A]): IList[NotPassedCause] =
      xs.list.collect { case -\/(x) => x }

    val toTestResult = new (AssertionResult ~> TestResult) {
      def apply[A](r: AssertionResult[A]): TestResult[A] = r match {
        case \/-(v) => TestResult(v)
        case -\/(c) => TestResult.nel(-\/(c), IList.empty)
      }
    }
  }

  implicit def toTestResult[A](result: => AssertionResult[A]): TestResult[A] =
    AssertionResult.toTestResult(result)

  implicit class AssertionResultSyntax[A](self: AssertionResult[A]) {

    def +>(result: => AssertionResult[A]): TestCase[A] = TestCase((self, result) match {
      case (\/-(_), \/-(v)) => TestResult(v)
      case (\/-(_), p @ -\/(_)) => TestResult.nel(p, IList.empty)
      case (p @ -\/(_), \/-(_)) => TestResult.nel(p, IList.empty)
      case (p1 @ -\/(_), p2 @ -\/(_)) => TestResult.nel(p1, IList.single(p2))
    })
  }

  implicit class AssertionResultByNameSyntax[A](self: => AssertionResult[A]) {

    def skip(reason: String): AssertionResult[A] = -\/(NotPassedCause.skip(reason))
  }

}
