package zio.interop

import zio._
import org.scalacheck._

/**
 * Temporary fork of zio.GenIO that overrides `genParallel` with ZManaged-based code
 * instead of `io.zipPar(parIo).map(_._1)`
 * because ZIP-PAR IS NON-DETERMINISTIC IN ITS SPAWNED EC TASKS (required for TestContext equality)
 * */
trait GenIOInteropCats {

  /**
   * Given a generator for `A`, produces a generator for `IO[E, A]` using the `IO.point` constructor.
   */
  def genSyncSuccess[E, A: Arbitrary]: Gen[IO[E, A]] =
    Arbitrary.arbitrary[A].map(IO.succeed)

  /**
   * Given a generator for `A`, produces a generator for `IO[E, A]` using the `IO.async` constructor.
   */
  def genAsyncSuccess[E, A: Arbitrary](implicit tc: TestContext0): Gen[IO[E, A]] =
//    Arbitrary.arbitrary[A].map(a => IO.effectAsync[E, A](k => k(IO.succeed(a))))
//    Arbitrary.arbitrary[A].map(a => IO.effectAsyncMaybe[E, A](_ => Some(IO.succeed(a))))
    // race condition
//    Arbitrary
//      .arbitrary[A]
//      .map(
//        a =>
//          IO.effectAsyncMaybe[E, A] { k =>
//            tc.execute(() => k(IO.succeed(a)))
//            None
//          }
//      )
    // no race condition
    Arbitrary
      .arbitrary[A]
      .map { a =>
        val _ = tc
        catz.taskConcurrentInstance[Any].asyncF[A](k => ZIO.effectTotal(k(Right(a)))).asInstanceOf[IO[E, A]]
      }

  /**
   * Randomly uses either `genSyncSuccess` or `genAsyncSuccess` with equal probability.
   */
  def genSuccess[E, A: Arbitrary](implicit tc: TestContext0): Gen[IO[E, A]] =
    Gen.oneOf(genSyncSuccess[E, A], genAsyncSuccess[E, A])

  /**
   * Given a generator for `E`, produces a generator for `IO[E, A]` using the `IO.fail` constructor.
   */
  def genSyncFailure[E: Arbitrary, A]: Gen[IO[E, A]] =
    Arbitrary.arbitrary[E].map(IO.fail[E])

  /**
   * Given a generator for `E`, produces a generator for `IO[E, A]` using the `IO.async` constructor.
   */
  def genAsyncFailure[E: Arbitrary, A]: Gen[IO[E, A]] =
    Arbitrary.arbitrary[E].map(err => IO.effectAsync[E, A](k => k(IO.fail(err))))

  /**
   * Randomly uses either `genSyncFailure` or `genAsyncFailure` with equal probability.
   */
  def genFailure[E: Arbitrary, A]: Gen[IO[E, A]] = Gen.oneOf(genSyncFailure[E, A], genAsyncFailure[E, A])

  /**
   * Randomly uses either `genSuccess` or `genFailure` with equal probability.
   */
  def genIO[E: Arbitrary, A: Arbitrary](implicit tc: TestContext0): Gen[IO[E, A]] =
    Gen.oneOf(genSuccess[E, A], genFailure[E, A])

  /**
   * Given a generator for `IO[E, A]`, produces a sized generator for `IO[E, A]` which represents a transformation,
   * by using some random combination of the methods `map`, `flatMap`, `mapError`, and any other method that does not change
   * the success/failure of the value, but may change the value itself.
   */
  def genLikeTrans[E: Arbitrary: Cogen, A: Arbitrary: Cogen](
    gen: Gen[IO[E, A]]
  )(implicit tc: TestContext0): Gen[IO[E, A]] = {
    val functions: IO[E, A] => Gen[IO[E, A]] = io =>
      Gen.oneOf(
        genOfFlatMaps[E, A](io)(genSuccess[E, A]),
        genOfMaps[E, A](io),
        genOfMapErrors[E, A](io),
//        genOfRace[E, A](io)
//        genOfParallel[E, A](io)(Gen.const(IO.succeed(null.asInstanceOf[A]): IO[E, A]))
        genOfParallel[E, A](io)(genAsyncSuccess[E, A])
//        genOfParallel[E, A](io)(genSyncSuccess[E, A])
//        genOfParallel[E, A](io)(genSuccess[E, A])
//        genOfParallelZManaged[E, A](io)(genSuccess[E, A])
      )
    gen.flatMap(io => genTransformations(functions)(io))
  }

  /**
   * Given a generator for `IO[E, A]`, produces a sized generator for `IO[E, A]` which represents a transformation,
   * by using methods that can have no effect on the resulting value (e.g. `map(identity)`, `io.race(never)`, `io.par(io2).map(_._1)`).
   */
  def genIdentityTrans[E, A: Arbitrary](gen: Gen[IO[E, A]])(implicit tc: TestContext0): Gen[IO[E, A]] = {
    val functions: IO[E, A] => Gen[IO[E, A]] = io =>
      Gen.oneOf(
        genOfIdentityFlatMaps[E, A](io),
        genOfIdentityMaps[E, A](io),
        genOfIdentityMapErrors[E, A](io),
//        genOfRace[E, A](io)
        genOfParallel[E, A](io)(genAsyncSuccess[E, A])
//        genOfParallel[E, A](io)(genSyncSuccess[E, A])
//        genOfParallel[E, A](io)(genSuccess[E, A])
//        genOfParallelZManaged[E, A](io)(genSuccess[E, A])
      )
    gen.flatMap(io => genTransformations(functions)(io))
  }

  private def genTransformations[E, A](
    functionGen: IO[E, A] => Gen[IO[E, A]]
  )(io: IO[E, A]): Gen[IO[E, A]] =
    Gen.sized { size =>
      def append1(n: Int, io: IO[E, A]): Gen[IO[E, A]] =
        if (n <= 0) io
        else
          (for {
            updatedIO <- functionGen(io)
          } yield updatedIO).flatMap(append1(n - 1, _))
      append1(size, io)
    }

  private def genOfMaps[E, A: Arbitrary: Cogen](io: IO[E, A]): Gen[IO[E, A]] =
    Arbitrary.arbitrary[A => A].map(f => io.map(f))

  private def genOfIdentityMaps[E, A](io: IO[E, A]): Gen[IO[E, A]] = Gen.const(io.map(identity))

  private def genOfMapErrors[E: Arbitrary: Cogen, A](io: IO[E, A]): Gen[IO[E, A]] =
    Arbitrary.arbitrary[E => E].map(f => io.mapError(f))

  private def genOfIdentityMapErrors[E, A](io: IO[E, A]): Gen[IO[E, A]] =
    Gen.const(io.mapError(identity))

  private def genOfFlatMaps[E, A: Cogen](io: IO[E, A])(
    gen: Gen[IO[E, A]]
  ): Gen[IO[E, A]] =
    gen.map(nextIO => io.flatMap(_ => nextIO))

  private def genOfIdentityFlatMaps[E, A](io: IO[E, A]): Gen[IO[E, A]] =
    Gen.const(io.flatMap(a => IO.succeed(a)))

  def genOfRace[E, A](io: IO[E, A]): Gen[IO[E, A]] =
    Gen.const(io.race(IO.never))

  def genOfParallel[E, A](io: IO[E, A])(gen: Gen[IO[E, A]]): Gen[IO[E, A]] =
    gen.map { parIo =>
//    gen.map { _ =>
      io.zipPar(parIo).map(_._1)
//      io
//      Promise
//        .make[Nothing, Unit]
//        .flatMap { p =>
//          ZManaged
//            .fromEffect(parIo *> p.succeed(()))
//            .fork
//            .use_(p.await *> io)
//        }
    }

  def genOfParallelZManaged[E, A](io: IO[E, A])(gen: Gen[IO[E, A]]): Gen[IO[E, A]] =
    gen.map { parIo =>
      Promise
        .make[Nothing, Unit]
        .flatMap { p =>
          ZManaged
            .fromEffect(parIo *> p.succeed(()))
            .fork
            .use_ {
//              p.await *>
              io
            }
        }
    }

}
