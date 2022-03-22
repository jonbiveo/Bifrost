package co.topl.consensus

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import akka.pattern.StatusReply
import co.topl.attestation.Address
import co.topl.consensus.ConsensusInterfaceSpec.TestInWithActor
import co.topl.consensus.NxtConsensus.ReceivableMessages.{ReadState, RollbackState}
import co.topl.consensus.NxtConsensus.State
import co.topl.modifier.block.Block
import co.topl.modifier.box.ArbitBox
import co.topl.nodeView.NodeViewTestHelpers.TestIn
import co.topl.nodeView.history.InMemoryKeyValueStore
import co.topl.nodeView.{NodeViewHolder, NodeViewTestHelpers}
import co.topl.utils._
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike

import scala.collection.AbstractIterator
import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble

class ConsensusInterfaceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with TestSettings
    with InMemoryKeyRingTestHelper
   // with NodeViewTestHelpers
    with NodeGenerators
    with MockFactory
    with OptionValues {

  behavior of "ConsensusStorage"

  it should "return default consensus params after no updates with empty storage" in {
    implicit val timeProvider: TimeProvider = mock[TimeProvider]
    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .onCall(() => System.currentTimeMillis())
    val genesisBlock = genesisBlockGen.sample.get

    withFreshView(genesisBlock) { testInWithActors =>
      val probe = createTestProbe[State]()
      testInWithActors.consensusViewRef ! ReadState(probe.ref)
      probe.expectMessage(NxtConsensus.State(testInWithActors.testIn.genesisView.state.totalStake, 0L, 0L, 0L))
    }
  }

  it should "update the consensus params when there is a new block published" in {
    implicit val timeProvider: TimeProvider = mock[TimeProvider]
    val genesisBlock = genesisBlockGen.sample.get
    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .onCall(() => System.currentTimeMillis())

    withFreshView(genesisBlock) { testInWithActor =>
      val probe = createTestProbe[State]()
      val newBlocks = generateBlocks(genesisBlock)(List(genesisBlock), keyRingCurve25519.addresses.head)
        .take(settings.application.consensusStoreVersionsToKeep / 2)
        .toList
      Thread.sleep(0.1.seconds.toMillis)
      newBlocks.foreach { block =>
        system.eventStream.tell(EventStream.Publish(NodeViewHolder.Events.SemanticallySuccessfulModifier(block)))
      }
      Thread.sleep(0.1.seconds.toMillis)
      testInWithActor.consensusViewRef ! ReadState(probe.ref)
      // Increasing the newBlock number by one as the height since we start out with a genesis block

      probe.expectMessage(
        NxtConsensus.State(
          testInWithActor.testIn.genesisView.state.totalStake,
          newBlocks.last.difficulty,
          0L,
          newBlocks.size + 1
        )
      )
    }
  }

  it should "load total stake from storage on start" in {

    implicit val timeProvider: TimeProvider = mock[TimeProvider]
    val genesisBlock = genesisBlockGen.sample.get

    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .onCall(() => System.currentTimeMillis())

    val probe = createTestProbe[State]()
    val store = InMemoryKeyValueStore.empty()
    val consensusStorageRef = spawn(
      NxtConsensus(settings, store),
      NxtConsensus.actorName
    )
    val newBlocks = generateBlocks(genesisBlock)(List(genesisBlock), keyRingCurve25519.addresses.head)
      .take(settings.application.consensusStoreVersionsToKeep / 2)
      .toList

    Thread.sleep(0.1.seconds.toMillis)
    newBlocks.foreach { block =>
      system.eventStream.tell(EventStream.Publish(NodeViewHolder.Events.SemanticallySuccessfulModifier(block)))
    }
    Thread.sleep(0.1.seconds.toMillis)
    consensusStorageRef ! ReadState(probe.ref)
    val params = probe.receiveMessage(0.1.seconds)
    testKit.stop(consensusStorageRef)

    // initialize a new consensus actor with the modified InMemoryKeyValueStore
    val newConsensusStorageRef = spawn(
      NxtConsensus(settings, store),
      NxtConsensus.actorName
    )
    newConsensusStorageRef ! ReadState(probe.ref)
    probe.expectMessage(params)
    testKit.stop(newConsensusStorageRef)
  }

  it should "roll back to a previous version" in {
    implicit val timeProvider: TimeProvider = mock[TimeProvider]
    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .onCall(() => System.currentTimeMillis())
    val genesisBlock = genesisBlockGen.sample.get

    withFreshView(genesisBlock) { testInWithActors =>
      val probe = createTestProbe[StatusReply[State]]()
      val newBlocks = generateBlocks(genesisBlock)(List(genesisBlock), keyRingCurve25519.addresses.head)
        .take(settings.application.consensusStoreVersionsToKeep / 2)
        .toList

      Thread.sleep(0.1.seconds.toMillis)

      newBlocks.foreach { block =>
        system.eventStream.tell(EventStream.Publish(NodeViewHolder.Events.SemanticallySuccessfulModifier(block)))
      }

      Thread.sleep(0.1.seconds.toMillis)

      testInWithActors.consensusViewRef ! RollbackState(newBlocks.head.id, probe.ref)
      // the first of the newBlocks would be at height 2 since it's the first one after the genesis block
      probe.expectMessage(
        StatusReply.success(
          State(testInWithActors.testIn.genesisView.state.totalStake, newBlocks.head.difficulty, 0L, 2L)
        )
      )
    }
  }

  it should "fail to roll back to a version beyond the number of versions to keep" in {
    implicit val timeProvider: TimeProvider = mock[TimeProvider]
    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .onCall(() => System.currentTimeMillis())
    val genesisBlock = genesisBlockGen.sample.get

    withFreshView(genesisBlock) { testInWithActors =>
      val probe = createTestProbe[StatusReply[State]]()
      val newBlocks = generateBlocks(genesisBlock)(List(genesisBlock), keyRingCurve25519.addresses.head)
        .take(settings.application.consensusStoreVersionsToKeep + 1)
        .toList

      Thread.sleep(0.1.seconds.toMillis)

      newBlocks.foreach { block =>
        system.eventStream.tell(EventStream.Publish(NodeViewHolder.Events.SemanticallySuccessfulModifier(block)))
      }

      Thread.sleep(0.1.seconds.toMillis)

      testInWithActors.consensusViewRef ! RollbackState(newBlocks.head.id, probe.ref)
      probe.receiveMessage(1.seconds).toString() shouldEqual "Error(Failed to roll back to the given version)"
    }
  }

  private def withFreshView(
    genesisBlock: Block
  )(test:         TestInWithActor => Unit)(implicit timeProvider: TimeProvider): Unit = {
    val genesis = NxtConsensus.Genesis(genesisBlock, NxtConsensus.State.empty)
    val testIn = ???//genesisNodeViewTestInputs(genesis)
    val consensusStorageRef =
      spawn(
        NxtConsensus(
          settings,
          InMemoryKeyValueStore(settings.application.consensusStoreVersionsToKeep)
        ),
        NxtConsensus.actorName
      )
    val nodeViewHolderRef = spawn(
      NodeViewHolder(
        settings,
        new ActorConsensusInterface(consensusStorageRef),
        () => Future.successful(???)//testIn.nodeView)
      )
    )
    val testInWithActor = TestInWithActor(testIn, nodeViewHolderRef, consensusStorageRef)
    test(testInWithActor)
    testKit.stop(nodeViewHolderRef)
    testKit.stop(consensusStorageRef)
  }

  private def generateBlocks(
    genesisBlock:   Block
  )(previousBlocks: List[Block], forgerAddress: Address): Iterator[Block] =
    new AbstractIterator[Block] {

      // Because the reward fee is 0, the genesis arbit box is never destroyed during forging, so we can re-use it
      private val arbitBox =
        previousBlocks.last.transactions
          .flatMap(_.newBoxes)
          .collectFirst { case a: ArbitBox if a.evidence == forgerAddress.evidence => a }
          .value
      private var previous3Blocks: List[Block] = previousBlocks.takeRight(3)

      override def hasNext: Boolean = true

      override def next(): Block =
        if (previous3Blocks.isEmpty) {
          previous3Blocks = List(genesisBlock)
          genesisBlock
        } else {
          val newBlock = ???
//            nextBlock(
//            previous3Blocks.last,
//            arbitBox,
//            previous3Blocks.map(_.timestamp),
//            forgerAddress,
//            new NxtLeaderElection(protocolVersioner)
//          )
          previous3Blocks = (previous3Blocks :+ newBlock).takeRight(3)
          newBlock
        }
    }
}

object ConsensusInterfaceSpec {

  case class TestInWithActor(
    testIn:            TestIn,
    nodeViewHolderRef: ActorRef[NodeViewHolder.ReceivableMessage],
    consensusViewRef:  ActorRef[NxtConsensus.ReceivableMessage]
  )
}
