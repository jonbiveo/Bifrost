package co.topl.genus.interpreters.orientdb

import cats.data.Chain
import cats.implicits._
import cats.effect.Sync
import com.orientechnologies.orient.core.sql.OCommandSQL
import com.tinkerpop.blueprints.impls.orient.{OrientBaseGraph, OrientDynaElementIterable, OrientElement, OrientVertex}

object OrientDb {

  implicit class OrientDbGraphOps(orientGraph: OrientBaseGraph) {
    import scala.jdk.CollectionConverters._

    protected def blockingIteratorQuery(query: String, args: Any*): Iterator[OrientElement] =
      orientGraph
        .command(new OCommandSQL(query))
        .execute[OrientDynaElementIterable](args: _*)
        .iterator()
        .asScala
        .map(_.asInstanceOf[OrientElement])

    private def getVertex[F[_]: Sync](query: GraphQuery[_]): Option[OrientVertex] = {
      val (q, args) = query.stringify
      blockingIteratorQuery(q, args: _*)
        .collect { case r: OrientVertex @unchecked => r }
        .nextOption()
    }

    def getNode[F[_]: Sync, T: NodeSchema](query: GraphQuery[T]): F[Option[T]] =
      Sync[F].blocking {
        val (q, args) = query.stringify
        blockingIteratorQuery(q, args: _*)
          .collect { case r: OrientVertex @unchecked => NodeSchema[T].decode(r.getProperties.asScala.toMap) }
          .nextOption()
      }

    def insertNode[F[_]: Sync, T: NodeSchema](t: T): F[Unit] = Sync[F].blocking {
      val schema = implicitly[NodeSchema[T]]
      val v = orientGraph.addVertex(s"class:${schema.name}")
      schema.encode(t).foreach { case (name, value) =>
        v.setProperty(name, value)
      }
      v.save()
    }

    def insertNodeBuilder[F[_]: Sync, T: NodeSchema](t: T): InsertNodeBuilder[F, T] =
      InsertNodeBuilder(t, Chain.empty, Chain.empty)

    case class InsertNodeBuilder[F[_]: Sync, T: NodeSchema] private (
      t:        T,
      outEdges: Chain[(String, Map[String, Any], GraphQuery[_])],
      inEdges:  Chain[(String, Map[String, Any], GraphQuery[_])]
    ) {

      def withEdgeTo[O, E: EdgeSchema[*, T, O]](edge: E, o: GraphQuery[O]): InsertNodeBuilder[F, T] =
        copy(outEdges =
          outEdges.append(
            (
              implicitly[EdgeSchema[E, T, O]].name,
              implicitly[EdgeSchema[E, T, O]].encode(edge),
              o
            )
          )
        )

      def withEdgeFrom[O, E: EdgeSchema[*, O, T]](edge: E, o: GraphQuery[O]): InsertNodeBuilder[F, T] =
        copy(inEdges =
          inEdges.append(
            (
              implicitly[EdgeSchema[E, O, T]].name,
              implicitly[EdgeSchema[E, O, T]].encode(edge),
              o
            )
          )
        )

      def run(): F[Unit] = Sync[F].blocking {
        val schema = implicitly[NodeSchema[T]]
        val v = orientGraph.addVertex(s"class:${schema.name}")
        schema.encode(t).foreach { case (name, value) =>
          v.setProperty(name, value)
        }
        v.save()
        outEdges.iterator.foreach { e =>
          val edge = orientGraph.addEdge(s"class:${e._1}", v, getVertex(e._3).getOrElse(???), "")
          e._2.foreach((edge.setProperty _).tupled)
        }
        inEdges.iterator.foreach { e =>
          val edge = orientGraph.addEdge(s"class:${e._1}", getVertex(e._3).getOrElse(???), v, "")
          e._2.foreach((edge.setProperty _).tupled)
        }
      }

    }

    def insertEdge[F[_]: Sync, T: EdgeSchema[*, Src, Dest], Src, Dest](
      t:         T,
      srcQuery:  GraphQuery[Src],
      destQuery: GraphQuery[Dest]
    ): F[Unit] = Sync[F].blocking {
      val schema = implicitly[EdgeSchema[T, Src, Dest]]
      val (_srcQuery, srcArgs) = srcQuery.stringify
      val (_destQuery, destArgs) = destQuery.stringify
      // TODO: Encode edge properties
      val query =
        s"""CREATE EDGE ${schema.name}" +
           |  FROM ($_srcQuery LIMIT 1)" +
           |  TO ($_destQuery LIMIT 1)
           |""".stripMargin
      val args = srcArgs ++ destArgs
      orientGraph.command(new OCommandSQL(query)).execute[Unit](args: _*)
    }
  }

}
