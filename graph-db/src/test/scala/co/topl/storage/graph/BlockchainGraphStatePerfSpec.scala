package co.topl.storage.graph

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.stream.scaladsl.{Sink, Source}
import cats.data.NonEmptyChain
import cats.scalatest.FutureEitherValues
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEachTestData, DoNotDiscover, OptionValues, TestData}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

@DoNotDiscover
class BlockchainGraphStatePerfSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with BeforeAndAfterAll
    with BeforeAndAfterEachTestData
    with FutureEitherValues
    with OptionValues
    with Matchers {

  behavior of "BlockchainGraphHistoryPerfSpec"

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  implicit val ec: ExecutionContext = system.executionContext

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Duration.Inf)

  private var dataDir: Path = _
  private var graph: OrientDBGraph = _
  private var underTest: BlockchainData = _

  private val count = 50000

  private val snapshotInterval = 500

  private val parallelism = 4

  it should "contain a block" in {
    val t = underTest
    import t._

    val head = Blockchain.currentHead.futureRightValue

    head.blockId shouldBe s"${count + 1}_1"

    val body = head.body.futureRightValue

    body.blockId shouldBe head.blockId
  }

  it should "find opened and unopened boxes" in {
    val t = underTest
    import t._
    val headBody = Blockchain.currentHead.flatMap(_.body).futureRightValue

    headBody.lookupUnopenedBox("1_1_1").futureLeftValue shouldBe BlockchainData.NotFound
    headBody.lookupUnopenedBox("2_1_2").futureRightValue shouldBe Box("2_1_2", 1, "1", 1)
  }

  it should s"store snapshots every $snapshotInterval blocks" in {
    val t = underTest
    import t._

    Source
      .unfoldAsync(1)(height =>
        Blockchain
          .blocksAtHeight(height)
          .map(_.value)
          .runWith(Sink.seq)
          .map {
            case Nil =>
              //
              None
            case blocks =>
              //
              Some((height + snapshotInterval, blocks))
          }
      )
      .mapConcat(identity)
      .mapAsync(1)(header => NonEmptyChain(CreateState(header.blockId)).run().value.map(_.value))
      .runWith(Sink.ignore)
      .futureValue
  }

  it should "find the same unopened and opened boxes" in {
    val t = underTest
    import t._

    val headBody = Blockchain.currentHead.flatMap(_.body).futureRightValue

    headBody.lookupUnopenedBox("1_1_1").futureLeftValue shouldBe BlockchainData.NotFound
    headBody.lookupUnopenedBox("2_1_2").futureRightValue shouldBe Box("2_1_2", 1, "1", 1)
  }

  private var testStartTimestampNano: Long = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    val schema = BlockchainGraphSchema.value

    dataDir = Paths.get(".", "target", "test", "db" + System.currentTimeMillis().toString)

//    graph = OrientDBGraph(schema, OrientDBGraph.InMemory)
    graph = OrientDBGraph(schema, OrientDBGraph.Local(dataDir))

    underTest = new BlockchainGraph(graph)

    logger.info(s"Preparing graph with $count blocks")
    testStartTimestampNano = System.nanoTime()
    prepareGraph()
    val deltaNanos = System.nanoTime() - testStartTimestampNano
    logger.info(s"Graph prepared after ${deltaNanos} nanos (${deltaNanos / 1_000_000_000d} seconds)")
  }

  override def afterAll(): Unit = {
    super.afterAll()
    graph.close()

    Files
      .walk(dataDir)
      .sorted(Comparator.reverseOrder[Path]())
      .iterator()
      .asScala
      .foreach(Files.delete)
  }

  override def beforeEach(testData: TestData): Unit = {
    super.beforeEach(testData)
    logger.info(s"Starting ${testData.name}")
    testStartTimestampNano = System.nanoTime()
  }

  override def afterEach(testData: TestData): Unit = {
    super.afterEach(testData)
    val deltaNanos = System.nanoTime() - testStartTimestampNano
    logger.info(s"Finishing `${testData.name}` after ${deltaNanos} nanos (${deltaNanos / 1_000_000_000d} seconds)")
  }

  private def prepareGraph(): Unit = {
    val t = underTest
    import t._

    NewBlockPackage.Genesis.nodeModifications.run().futureRightValue
    NewBlockPackage.Genesis.edgeModifications.run().futureRightValue

    Source
      .fromIterator(() => (2 to (count + 1)).grouped(count / parallelism))
      .flatMapMerge(
        parallelism,
        partition =>
          Source(partition)
            .map(NewBlockPackage.forHeight(_).nodeModifications)
            .mapAsync(1)(v => v.run().value.map(_.value))
      )
      .runWith(Sink.ignore)
      .futureValue(Timeout(10.minutes))

    Source
      .fromIterator(() => (2 to (count + 1)).grouped(count / parallelism))
      .flatMapMerge(
        parallelism,
        partition =>
          Source(partition)
            .map(NewBlockPackage.forHeight(_).edgeModifications)
            .mapAsync(1)(v => v.run().value.map(_.value))
      )
      .runWith(Sink.ignore)
      .futureValue(Timeout(10.minutes))

    NonEmptyChain(SetHead(s"${count + 1}_1")).run().futureRightValue
  }
}

case class NewBlockPackage(
  parentBlockId:       Option[String],
  header:              BlockHeader,
  body:                BlockBody,
  transactionPackages: List[NewTransactionPackage]
) {

  def nodeModifications: NonEmptyChain[BlockchainModification] =
    NonEmptyChain
      .fromSeq(
        List(CreateBlockHeader(header), CreateBlockBody(body)) ++
        transactionPackages.flatMap(t =>
          List(CreateTransaction(t.transaction)) ++
          t.newBoxes
            .flatMap(newBox => List(CreateBox(newBox)))
        )
      )
      .get

  def edgeModifications: NonEmptyChain[BlockchainModification] =
    NonEmptyChain
      .fromSeq(
        List(
          AssociateBodyToHeader(body.blockId, header.blockId)
        ) ++ parentBlockId.map(AssociateBlockToParent(header.blockId, _)) ++
        transactionPackages.flatMap(t =>
          List(
            AssociateTransactionToBody(t.transaction.transactionId, body.blockId, index = 0)
          ) ++ t.openedBoxIds.flatMap(id => List(AssociateBoxOpener(id, t.transaction.transactionId))) ++ t.newBoxes
            .flatMap(newBox => List(AssociateBoxCreator(newBox.boxId, t.transaction.transactionId, minted = false)))
        )
      )
      .get

}

object NewBlockPackage {

  val Genesis: NewBlockPackage = NewBlockPackage(
    parentBlockId = None,
    header = BlockHeader(
      "1_1",
      timestamp = 1L,
      publicKey = "topl",
      signature = "topl",
      height = 1,
      difficulty = 1,
      txRoot = "topl",
      bloomFilter = "topl",
      version = 1
    ),
    body = BlockBody("1_1"),
    transactionPackages = List(
      NewTransactionPackage(
        Transaction(
          "1_1_1",
          "0",
          0,
          data = None,
          minting = true,
          attestation = Map("topl" -> "BobSaidSo")
        ),
        openedBoxIds = Nil,
        newBoxes = List(
          Box(
            "1_1_1",
            boxType = 1,
            value = "1000000",
            nonce = 1
          )
        )
      )
    )
  )

  def forHeight(height: Int): NewBlockPackage = {
    val newBlockId = s"${height}_1"
    NewBlockPackage(
      parentBlockId = Some(s"${height - 1}_1"),
      header = BlockHeader(
        blockId = newBlockId,
        timestamp = height,
        publicKey = "topl",
        signature = "topl",
        height = height,
        difficulty = 1,
        txRoot = "topl",
        bloomFilter = "topl",
        version = 1
      ),
      body = BlockBody(
        blockId = newBlockId
      ),
      transactionPackages = List(
        NewTransactionPackage(
          Transaction(
            newBlockId + "_1",
            "0",
            height + 1,
            data = None,
            minting = true,
            attestation = Map("topl" -> "BobSaidSo")
          ),
          openedBoxIds = List(s"${height - 1}_1_1"),
          newBoxes = List(
            Box(
              newBlockId + "_1",
              boxType = 1,
              value = "1",
              nonce = 1
            ),
            Box(
              newBlockId + "_2",
              boxType = 1,
              value = "1",
              nonce = 1
            )
          )
        )
      )
    )
  }
}

case class NewTransactionPackage(transaction: Transaction, openedBoxIds: List[String], newBoxes: List[Box])
