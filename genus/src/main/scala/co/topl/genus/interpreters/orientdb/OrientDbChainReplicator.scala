package co.topl.genus.interpreters.orientdb

import akka.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import cats.data.OptionT
import cats.effect.Async
import cats.implicits._
import cats.{~>, Parallel, Traverse}
import co.topl.algebras.ToplRpc
import co.topl.catsakka._
import co.topl.codecs.bytes.tetra.instances._
import co.topl.codecs.bytes.typeclasses.implicits._
import co.topl.genus.algebras.ChainReplicatorAlgebra
import co.topl.genus.interpreters.orientdb.EdgeSchemas._
import co.topl.genus.interpreters.orientdb.NodeSchemas._
import co.topl.genus.interpreters.orientdb.OrientDb._
import co.topl.models.{BlockHeaderV2, Transaction, TypedIdentifier}
import co.topl.typeclasses.implicits._
import com.tinkerpop.blueprints.impls.orient.OrientBaseGraph

object OrientDbChainReplicator {

  def make[F[_]: Async: Parallel: FToFuture: RunnableGraph ~> *[_]](
    graph: OrientBaseGraph
  ): F[ChainReplicatorAlgebra[F, SourceMatNotUsed]] =
    Async[F].delay {
      new ChainReplicatorAlgebra[F, SourceMatNotUsed] {
        private type RpcClient = ToplRpc[F, SourceMatNotUsed]
        def replicateFrom(client: ToplRpc[F, SourceMatNotUsed]): F[Unit] =
          replicateHistory(client) >> replicateLive(client)

        /**
         * Replicates past blockchain data (up to the node's _current_ head) into the graph database
         */
        private def replicateHistory(client: RpcClient): F[Unit] =
          for {
            adoptionsSource <- client.blockAdoptions()
            latestBlockId   <- Async[F].fromFuture(adoptionsSource.toMat(Sink.head)(Keep.right).liftTo[F])
            latestHeader    <- OptionT(client.fetchHeader(latestBlockId)).getOrElse(???)
            graphHeight     <- currentGraphHeight()
            missingHeights = Range.Long.inclusive(graphHeight + 1, latestHeader.height, 1)
            _ <- Source(missingHeights)
              .mapAsyncF(10)(height => OptionT(client.fetchBlockIdAtHeight(height)).getOrElse(???))
              .toMat(
                Flow[TypedIdentifier]
                  .mapAsyncF(100)(id => fetchSingle(client)(id).map(t => (id, t._1, t._2)))
                  .mapAsyncF(1)((save _).tupled)
                  .toMat(Sink.ignore)(Keep.right)
              )(Keep.right)
              .liftTo[F]
          } yield ()

        /**
         * Replicates blockchain data (and missing ancestors) as it is announced by the node
         */
        private def replicateLive(client: RpcClient): F[Unit] =
          client
            .blockAdoptions()
            .flatMap(
              _.toMat(
                Flow[TypedIdentifier]
                  .mapAsyncF(1)(fetchRecursively(client))
                  .mapConcat(identity)
                  .mapAsyncF(1)((save _).tupled)
                  .toMat(Sink.ignore)(Keep.right)
              )(Keep.right).liftTo[F]
            )

        private def fetchSingle(client: RpcClient)(
          id:                           TypedIdentifier
        ): F[(BlockHeaderV2, Seq[Transaction])] =
          OptionT(client.fetchHeader(id))
            .parProduct(
              OptionT(client.fetchBody(id))
                .flatMap(body => body.parTraverse(transactionId => OptionT(client.fetchTransaction(transactionId))))
            )
            .getOrElse(???)

        /**
         * Recursively fetch the block (and its transactions) and its ancestors until an ancestor is found in the local
         * database.
         */
        private def fetchRecursively(client: RpcClient)(
          id:                                TypedIdentifier
        ): F[List[(TypedIdentifier, BlockHeaderV2, Seq[Transaction])]] =
          OptionT(client.fetchHeader(id)).isEmpty
            .tupleLeft((id, Nil))
            .flatMap(
              _.iterateWhileM { case ((id, accumulator), _) =>
                fetchSingle(client)(id)
                  .flatMap { case (header, transactions) =>
                    val t = (id, header, transactions)
                    OptionT(
                      graph.getNode(
                        NodesByClass[NodeTypes.Header](
                          Where.PropEquals("id", header.parentHeaderId.immutableBytes.toBase58)
                        )
                      )
                    ).isEmpty
                      .map(((header.parentHeaderId, t +: accumulator), _))
                  }
              }(_._2)
            )

        private def save(
          id:           TypedIdentifier,
          header:       BlockHeaderV2,
          transactions: Seq[Transaction]
        ): F[Unit] =
          for {
            headerIdString <- id.immutableBytes.toBase58.pure[F]
            _              <- saveHeader(headerIdString, header)
            _              <- saveTransactions(headerIdString, transactions)
          } yield ()

        private def saveHeader(idString: String, header: BlockHeaderV2) =
          graph.insertNode(
            NodeTypes.Header(
              idString,
              header.parentHeaderId.immutableBytes.toBase58,
              header.parentSlot,
              header.txRoot.data.toBase58,
              header.timestamp,
              header.height,
              header.slot,
              header.address.immutableBytes.toBase58
            )
          )

        private def saveTransactions[G[_]: Traverse](blockIdString: String, transactions: G[Transaction]) =
          transactions.traverseWithIndexM((transaction, index) =>
            for {
              transactionIdString <- transaction.id.asTypedBytes.immutableBytes.toBase58.pure[F]
              _ <- graph.insertNode(
                NodeTypes.Transaction(
                  transactionIdString,
                  transaction.chronology.creation,
                  transaction.chronology.minimumSlot,
                  transaction.chronology.maximumSlot,
                  transaction.data.map(_.data.value)
                )
              )
              _ <- graph.insertEdge(
                EdgeTypes.TransactionToHeader(index.toShort),
                NodesByClass[NodeTypes.Transaction](Where.PropEquals("id", transactionIdString)),
                NodesByClass[NodeTypes.Header](Where.PropEquals("id", blockIdString))
              )
              _ <- graph.insertEdge(
                EdgeTypes.TransactionToHeader(index.toShort),
                NodesByClass[NodeTypes.Transaction](Where.PropEquals("id", transactionIdString)),
                NodesByClass[NodeTypes.Header](Where.PropEquals("id", blockIdString))
              )
              _ <- transaction.inputs.traverseWithIndexM((input, inputIndex) =>
                graph
                  .insertNodeBuilder(
                    NodeTypes.TransactionInput(
                      input.proposition.immutableBytes.toBase58,
                      input.proof.immutableBytes.toBase58
                    )
                  )
                  .withEdgeFrom(
                    EdgeTypes.TransactionToInput(inputIndex.toShort),
                    NodesByClass[NodeTypes.Transaction](Where.PropEquals("id", transactionIdString))
                  )
                  .withEdgeTo(
                    EdgeTypes.InputToOutput,
                    Raw[NodeTypes.TransactionOutput](
                      // TODO: Does this work?  Replace with an OrientDB Function?
                      s"""
                         |SELECT expand(tOut) FROM(
                         |  SELECT expand(outE('${EdgeSchemas.inputToOutputEdgeSchema.name}')) AS tOutEdgeProps,
                         |         out('${EdgeSchemas.inputToOutputEdgeSchema.name}') AS tOut
                         |    FROM (SELECT FROM ${NodeSchemas.transactionNodeSchema.name} WHERE id = ?)
                         |    WHERE tOutEdgeProps.index = ?
                         |)
                         |""".stripMargin,
                      Array(input.boxId.transactionId.immutableBytes.toBase58, input.boxId.transactionOutputIndex)
                    )
                  )
                  .run()
              )
              _ <- transaction.outputs.traverseWithIndexM((output, outputIndex) =>
                graph
                  .insertNodeBuilder(
                    NodeTypes.TransactionOutput(
                      output.address.immutableBytes.toBase58,
                      output.minting
                    )
                  )
                  .withEdgeFrom(
                    EdgeTypes.TransactionToOutput(outputIndex.toShort),
                    NodesByClass[NodeTypes.Transaction](Where.PropEquals("id", transactionIdString))
                  )
                  .run()
              )
            } yield ()
          )

        private def currentGraphHeight(): F[Long] =
          OptionT(
            graph.getNode(
              Trace[NodeTypes.CanonicalHead.type]()
                .out[EdgeTypes.CanonicalHead.type, NodeTypes.Header]
            )
          ).map(_.height).getOrElse(???)
      }
    }
}
