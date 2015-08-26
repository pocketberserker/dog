package dog

import scalaz._
import scalaprops._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException

package object props {

  implicit def testcase[A](implicit E: Endo[dog.Param] = dog.Param.id): AsProperty[TestCase[A]] =
    AsProperty.from (c => Property.propFromResult(c.run(E) match {
      case Done(results) => results.list match {
        case List(\/-(_)) => Result.Proven
        case List(-\/(Skipped(reason))) => Result.Ignored(reason)
        case _ => Result.Falsified(IList.empty)
      }
      case Error(e::_, _) => Result.Exception(IList.empty, e)
      case _ => Result.Falsified(IList.empty)
    }))

  implicit class TestCaseSynax[A](val self: TestCase[A]) {

    def to(implicit E: Endo[dog.Param] = dog.Param.id): Property = testcase.asProperty(self)
  }

  implicit def assertion[A]: AsProperty[AssertionResult[A]] =
    AsProperty.from(a => Property.propFromResult(a match {
      case \/-(_) => Result.Proven
      case -\/(Skipped(reason)) => Result.Ignored(reason)
      case -\/(Violated(_)) => Result.Falsified(IList.empty)
    }))

  implicit class AssertionResultSynax[A](val self: AssertionResult[A]) {

    def to: Property = assertion.asProperty(self)
  }

  private[this] def checkResultToTestResult[A](result: CheckResult[A], value: A): TestResult[A] = result match {
    case _: CheckResult.Proven | _: CheckResult.Passed => TestResult(value)
    case _: CheckResult.Exhausted | _: CheckResult.Falsified =>
      TestResult.nel(-\/(NotPassedCause.violate(result.toString)), List())
    case e: CheckResult.GenException =>
      TestResult.error(List(e.exception), List())
    case e: CheckResult.PropException =>
      TestResult.error(List(e.exception), List(NotPassedCause.violate(e.toString)))
    case e: CheckResult.Timeout =>
      TestResult.error(List(), List(NotPassedCause.violate(e.toString)))
    case e: CheckResult.Ignored =>
      TestResult.nel(-\/(NotPassedCause.skip(e.reason)), List())
  }

  implicit class PropertySyntax(val self: Property) {

    def toTestCase(param: scalaprops.Param = scalaprops.Param.withCurrentTimeSeed()): TestCase[Unit] =
      Kleisli.kleisli((paramEndo: Endo[dog.Param]) => try {
        val p = paramEndo(dog.Param.default)
        val cancel = new AtomicBoolean(false)
        checkResultToTestResult((p.executorService match {
          case Some(s) => Task(self.check(param, cancel, count => ()))(s)
          case None => Task(self.check(param, cancel, count => ()))
        }).runFor(param.timeout))
      } catch {
        case e: TimeoutException => TestResult.error(List(e), List())
        case e: Throwable => TestResult.error(List(e), List())
      } finally {
        cancel.set(true)
      })
  }
}