package co.topl.networking.typedprotocols

import cats.data.Chain
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Ref}
import cats.implicits._
import co.topl.networking.{Parties, TypedProtocol, TypedProtocolState}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks, ScalaCheckPropertyChecks}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import scala.concurrent.duration._
import scala.util.Random

class LatencySpec
    extends AnyFlatSpec
    with BeforeAndAfterAll
    with MockFactory
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaCheckDrivenPropertyChecks
    with EitherValues
    with OptionValues {

  type F[A] = IO[A]

  behavior of "Latency"
  import Latency._
  implicit private val logger: Logger[F] = Slf4jLogger.getLogger[F]

  it should "play ping pong" in {

    val executorA = TypedProtocol[F](Parties.A)
    val executorB = TypedProtocol[F](Parties.B)

    val localStateRefA =
      Ref.of[F, Latency.LocalPingState](Latency.LocalPingState(Instant.now(), Chain.empty)).unsafeRunSync()

    val localStateRefB =
      Ref.of[F, Latency.LocalPingState](Latency.LocalPingState(Instant.now(), Chain.empty)).unsafeRunSync()

    val stateTransitionsA = new StateTransitions[F](localStateRefA)
    val stateTransitionsB = new StateTransitions[F](localStateRefB)

    val computation =
      for {
        none <- TypedProtocolState(Parties.B.some, States.None()).pure[F]
        idleA <- {
          import stateTransitionsA._
          executorA(Messages.Start())(none).nextState
        }
        idleB <- {
          import stateTransitionsB._
          executorB(Messages.Start())(none).nextState
        }
        (idleA1, idleB1, _) <- (idleA, idleB, 20)
          .iterateUntilM { case (idleA, idleB, iterationNumber) =>
            (
              {
                import stateTransitionsA._
                executorA(Messages.Ping())(idleA).nextState
              }, {
                import stateTransitionsB._
                executorB(Messages.Ping())(idleB).nextState
              }
            ).tupled
              .flatTap { case (nextA, nextB) =>
                (nextA shouldBe nextB).pure[F]
              }
              .flatMap { case (nextA, nextB) =>
                Async[F].delayBy(
                  (
                    {
                      import stateTransitionsA._
                      executorA(Messages.Pong())(nextA).nextState
                    }, {
                      import stateTransitionsB._
                      executorB(Messages.Pong())(nextB).nextState
                    }
                  ).tupled,
                  Random.nextInt(200).milli
                )
              }
              .flatTap { case (nextA, nextB) =>
                (nextA shouldBe nextB).pure[F]
              }
              .map { case (nextA, nextB) =>
                (nextA, nextB, iterationNumber - 1)
              }
          }(_._3 <= 0)
        done <- {
          import stateTransitionsA._
          executorA(Messages.Done())(idleA1).nextState
        }
      } yield done

    val protocol5 = computation.unsafeRunSync()

    protocol5.currentAgent shouldBe None
    protocol5.currentState shouldBe a[States.Done]

  }
}
