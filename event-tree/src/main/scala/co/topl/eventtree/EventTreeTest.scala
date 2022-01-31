package co.topl.eventtree

import cats.data.OptionT
import cats.effect._
import cats.implicits._
import co.topl.codecs.bytes.{ByteCodec, Reader, Writer}
import co.topl.commoninterpreters.LevelDbStore
import co.topl.models.{Bytes, TypePrefix, TypedBytes, TypedIdentifier}
import co.topl.typeclasses.Identifiable
import co.topl.typeclasses.implicits._

import java.nio.file.Files

object EventTreeTest extends IOApp.Simple {
  type F[A] = IO[A]

  case class Tx(id: TypedIdentifier, from: (String, Long), to: String)

  implicit val identifiableTx: Identifiable[Tx] =
    new Identifiable[Tx] {
      def idOf(tx: Tx): TypedIdentifier = tx.id

      def typePrefix: TypePrefix = 1: Byte
    }

  implicit val txByteCodec: ByteCodec[Tx] =
    new ByteCodec[Tx] {

      def encode(t: Tx, writer: Writer): Unit = {
        val idByteArray = t.id.allBytes.toArray
        writer.putInt(idByteArray.length)
        writer.putBytes(idByteArray)
        writer.putByteString(t.from._1)
        writer.putLong(t.from._2)
        writer.putByteString(t.to)
      }

      def decode(reader: Reader): Tx =
        Tx(
          TypedBytes(Bytes(reader.getBytes(reader.getInt()))),
          (reader.getByteString(), reader.getLong()),
          reader.getByteString()
        )
    }

  case class LedgerDelta(previousTxId: TypedIdentifier, senderPreviousBalance: Long, tx: Tx)

  implicit val identifiableLedgerDelta: Identifiable[LedgerDelta] =
    new Identifiable[LedgerDelta] {
      def idOf(l: LedgerDelta): TypedIdentifier = l.tx.id

      def typePrefix: TypePrefix = 1: Byte
    }

  implicit val ledgerDeltaByteCodec: ByteCodec[LedgerDelta] =
    new ByteCodec[LedgerDelta] {

      def encode(t: LedgerDelta, writer: Writer): Unit = {
        val previousTxIdByteArray = t.previousTxId.allBytes.toArray
        writer.putInt(previousTxIdByteArray.length)
        writer.putBytes(previousTxIdByteArray)
        writer.putLong(t.senderPreviousBalance)
        txByteCodec.encode(t.tx, writer)
      }

      def decode(reader: Reader): LedgerDelta =
        LedgerDelta(
          TypedBytes(Bytes(reader.getBytes(reader.getInt()))),
          reader.getLong(),
          txByteCodec.decode(reader)
        )
    }

  implicit val bytesByteCodec: ByteCodec[Bytes] =
    new ByteCodec[Bytes] {

      def encode(t: Bytes, writer: Writer): Unit = {
        writer.putInt(t.length.toInt)
        writer.putBytes(t.toArray)
      }

      def decode(reader: Reader): Bytes =
        Bytes(reader.getBytes(reader.getInt()))
    }

  def run: IO[Unit] = for {
    eventStore <- LevelDbStore.Eval.make[F, Tx](Files.createTempDirectory("event-store-"))
    deltaStore <- LevelDbStore.Eval.make[F, LedgerDelta](Files.createTempDirectory("delta-store-"))
    tree       <- ParentChildTree.FromRef.make[F, TypedIdentifier]
    txData = List(
      ("a".asTxId, "alice", 10L, "bob"),
      ("b".asTxId, "alice", 10L, "chelsea"),
      ("c1".asTxId, "alice", 5L, "bob"),
      ("c2".asTxId, "alice", 5L, "chelsea"),
      ("d1c1".asTxId, "bob", 5L, "chelsea"),
      ("e1d1c1".asTxId, "chelsea", 5L, "alice")
    )
    _ = println(txData)
    _ <- txData.foldLeftM[F, Unit](()) { case (_, (txId, from, amount, to)) =>
      eventStore.put(txId, Tx(txId, (from, amount), to))
    }
    _           <- tree.associate(child = "e1d1c1".asTxId, parent = "d1c1".asTxId)
    _           <- tree.associate(child = "d1c1".asTxId, parent = "c1".asTxId)
    _           <- tree.associate(child = "c1".asTxId, parent = "b".asTxId)
    _           <- tree.associate(child = "c2".asTxId, parent = "b".asTxId)
    _           <- tree.associate(child = "b".asTxId, parent = "a".asTxId)
    _           <- tree.associate(child = "a".asTxId, parent = "-1".asTxId)
    ledgerStore <- LevelDbStore.Eval.make[F, Bytes](Files.createTempDirectory("ledger-store-"))
    initialEventId = "-1".asTxId
    _ <- ledgerStore.put(Ledger.Eval.CurrentEventIdId, initialEventId.allBytes)
    ledger = Ledger.Eval.make[F](ledgerStore)
    _ <- ledger.modifyBalanceOf("alice", _ => 100L.some.pure[F])
    eventTree <- EventTree.Eval.make[F, Tx, Ledger[F], LedgerDelta](
      initialState = Sync[F].delay(ledger),
      initialEventId = Sync[F].delay(initialEventId),
      eventAsDelta = (tx, ledger) =>
        for {
          currentId      <- ledger.eventId
          currentBalance <- OptionT(ledger.balanceOf(tx.from._1)).getOrElse(0L)
        } yield LedgerDelta(currentId, currentBalance, tx),
      applyDelta = (ledger, delta) =>
        for {
          _ <- ledger.modifyBalanceOf(delta.tx.from._1, b => (b.getOrElse(0L) - delta.tx.from._2).some.pure[F])
          _ <- ledger.modifyBalanceOf(delta.tx.to, b => (b.getOrElse(0L) + delta.tx.from._2).some.pure[F])
          _ <- ledger.setEventId(delta.tx.id)
        } yield ledger,
      unapplyDelta = (ledger, delta) =>
        for {
          _ <- ledger.modifyBalanceOf(delta.tx.from._1, _ => delta.senderPreviousBalance.some.pure[F])
          _ <- ledger.modifyBalanceOf(delta.tx.to, b => (b.getOrElse(0L) - delta.tx.from._2).some.pure[F])
          _ <- ledger.setEventId(delta.previousTxId)
        } yield ledger,
      eventStore = eventStore,
      deltaStore = deltaStore,
      parentChildTree = tree
    )
    ledgerC1     <- eventTree.stateAt("c1".asTxId)
    _            <- printLedgerBalances(ledgerC1)
    ledgerC2     <- eventTree.stateAt("c2".asTxId)
    _            <- printLedgerBalances(ledgerC2)
    ledgerE1D1C1 <- eventTree.stateAt("e1d1c1".asTxId)
    _            <- printLedgerBalances(ledgerE1D1C1)
  } yield ()

  private def printLedgerBalances(ledger: Ledger[F]): F[Unit] =
    (ledger.balanceOf("alice"), ledger.balanceOf("bob"), ledger.balanceOf("chelsea")).mapN(
      (aBalance, bBalance, cBalance) => println(show"Balances: alice=$aBalance bob=$bBalance chelsea=$cBalance")
    )

  implicit class StringOps(string: String) {
    def asTxId: TypedIdentifier = TypedBytes(1: Byte, Bytes(string.getBytes()))
  }
}
