package co.topl.eventtree

import cats.data.OptionT
import cats.effect._
import cats.implicits._
import cats.{Functor, Semigroupal}
import co.topl.codecs.bytes.{ByteCodec, Reader, Writer}
import co.topl.commoninterpreters.LevelDbStore
import co.topl.models.{Bytes, TypePrefix, TypedBytes, TypedIdentifier}
import co.topl.typeclasses.Identifiable
import co.topl.typeclasses.implicits._

import java.nio.file.Files

object LedgerTreeTest extends IOApp.Simple {
  type F[A] = IO[A]
  import LedgerTreeTestSupport._

  def run: IO[Unit] = for {
    eventStore <- LevelDbStore.Eval.make[F, Tx](Files.createTempDirectory("event-store-"))
    deltaStore <- LevelDbStore.Eval.make[F, LedgerUnapply](Files.createTempDirectory("delta-store-"))
    tree       <- ParentChildTree.FromRef.make[F, TypedIdentifier]
    _ = println(txData)
    _ <- txData.foldLeftM[F, Unit](()) { case (_, (txId, from, amount, to)) =>
      eventStore.put(txId, Tx(txId, (from, amount), to))
    }
    _           <- txAssociations.foldLeftM[F, Unit](()) { case (_, (c, p)) => tree.associate(c.asTxId, p.asTxId) }
    ledgerStore <- LevelDbStore.Eval.make[F, Bytes](Files.createTempDirectory("ledger-store-"))
    initialEventId = "-1".asTxId
    _ <- ledgerStore.put(Ledger.Eval.CurrentEventIdId, initialEventId.allBytes)
    ledger = Ledger.Eval.make[F](ledgerStore)
    _ <- ledger.modifyBalanceOf("alice", _ => 100L.some.pure[F])
    eventTree <- EventSourcedState.OfTree.make[F, Tx, Ledger[F], LedgerUnapply](
      initialState = Sync[F].delay(ledger),
      initialEventId = Sync[F].delay(initialEventId),
      eventAsUnapplyEvent = (tx, ledger) =>
        for {
          currentId      <- ledger.eventId
          currentBalance <- OptionT(ledger.balanceOf(tx.from._1)).getOrElse(0L)
        } yield LedgerUnapply(currentId, currentBalance, tx),
      applyEvent = (ledger, tx) =>
        for {
          _ <- ledger.modifyBalanceOf(tx.from._1, b => (b.getOrElse(0L) - tx.from._2).some.pure[F])
          _ <- ledger.modifyBalanceOf(tx.to, b => (b.getOrElse(0L) + tx.from._2).some.pure[F])
          _ <- ledger.setEventId(tx.id)
        } yield ledger,
      unapplyEvent = (ledger, unapply) =>
        for {
          _ <- ledger.modifyBalanceOf(unapply.tx.from._1, _ => unapply.senderPreviousBalance.some.pure[F])
          _ <- ledger.modifyBalanceOf(unapply.tx.to, b => (b.getOrElse(0L) - unapply.tx.from._2).some.pure[F])
          _ <- ledger.setEventId(unapply.previousTxId)
        } yield ledger,
      eventStore = eventStore,
      unapplyEventStore = deltaStore,
      parentChildTree = tree
    )
    ledgerC1     <- eventTree.stateAt("c1".asTxId)
    _            <- printLedgerBalances[F](ledgerC1) // Expect: alice=75, bob=15, chelsea=10
    ledgerC2     <- eventTree.stateAt("c2".asTxId)
    _            <- printLedgerBalances[F](ledgerC2) // Expect: alice=75, bob=10, chelsea=15
    ledgerE1D1C1 <- eventTree.stateAt("e1d1c1".asTxId)
    _            <- printLedgerBalances[F](ledgerE1D1C1) // Expect: alice=80, bob=10, chelsea=10
  } yield ()
}

object LedgerTreeTestSupport {

  case class Tx(id: TypedIdentifier, from: (String, Long), to: String)

  val txData = List(
    ("a".asTxId, "alice", 10L, "bob"),
    ("b".asTxId, "alice", 10L, "chelsea"),
    ("c1".asTxId, "alice", 5L, "bob"),
    ("c2".asTxId, "alice", 5L, "chelsea"),
    ("d1c1".asTxId, "bob", 5L, "chelsea"),
    ("e1d1c1".asTxId, "chelsea", 5L, "alice")
  )

  val txAssociations = List(
    "e1d1c1" -> "d1c1",
    "d1c1"   -> "c1",
    "c1"     -> "b",
    "b"      -> "a",
    "a"      -> "-1",
    "c2"     -> "b"
  )

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

  case class LedgerUnapply(previousTxId: TypedIdentifier, senderPreviousBalance: Long, tx: Tx)

  implicit val ledgerDeltaByteCodec: ByteCodec[LedgerUnapply] =
    new ByteCodec[LedgerUnapply] {

      def encode(t: LedgerUnapply, writer: Writer): Unit = {
        val previousTxIdByteArray = t.previousTxId.allBytes.toArray
        writer.putInt(previousTxIdByteArray.length)
        writer.putBytes(previousTxIdByteArray)
        writer.putLong(t.senderPreviousBalance)
        txByteCodec.encode(t.tx, writer)
      }

      def decode(reader: Reader): LedgerUnapply =
        LedgerUnapply(
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

  def printLedgerBalances[F[_]: Functor: Semigroupal](ledger: Ledger[F]): F[Unit] =
    (ledger.balanceOf("alice"), ledger.balanceOf("bob"), ledger.balanceOf("chelsea")).mapN(
      (aBalance, bBalance, cBalance) => println(show"Balances: alice=$aBalance bob=$bBalance chelsea=$cBalance")
    )

  implicit class StringOps(string: String) {
    def asTxId: TypedIdentifier = TypedBytes(1: Byte, Bytes(string.getBytes()))
  }
}
