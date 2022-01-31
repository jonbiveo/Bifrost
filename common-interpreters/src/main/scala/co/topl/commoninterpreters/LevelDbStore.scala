package co.topl.commoninterpreters

import cats.data.OptionT
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.algebras.Store
import co.topl.codecs.bytes.ByteCodec
import co.topl.codecs.bytes.implicits._
import co.topl.models._
import co.topl.typeclasses._
import org.iq80.leveldb._
import org.iq80.leveldb.impl.Iq80DBFactory

import java.nio.file.Path

object LevelDbStore {

  private val dbFactory = new Iq80DBFactory

  object Eval {

    def make[F[_]: Sync, T: ByteCodec](path: Path): F[Store[F, T]] =
      Sync[F]
        .blocking(dbFactory.open(path.toFile, new Options))
        .map(db =>
          new Store[F, T] {

            def get(id: TypedIdentifier): F[Option[T]] =
              OptionT(Sync[F].blocking(Option(db.get(id.allBytes.toArray))))
                .map(Bytes(_).decoded[T])
                .value

            def put(id: TypedIdentifier, t: T): F[Unit] =
              Sync[F].blocking {
                db.put(id.allBytes.toArray, t.bytes.toArray)
              }

            def remove(id: TypedIdentifier): F[Unit] =
              Sync[F].blocking {
                db.delete(id.allBytes.toArray)
              }
          }
        )
  }
}
