package co.topl.api

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.{ByteString, Timeout}
import cats.data.NonEmptyChain
import co.topl.akkahttprpc.ThrowableSupport.Standard._
import co.topl.consensus.KeyManager.{KeyView, StartupKeyView}
import co.topl.consensus._
import co.topl.http.HttpService
import co.topl.modifier.block.Block
import co.topl.network.BifrostSyncInfo
import co.topl.network.utils.NetworkTimeProvider
import co.topl.nodeView._
import co.topl.nodeView.history.{History, InMemoryKeyValueStore}
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.State
import co.topl.rpc.ToplRpcServer
import co.topl.utils.{DiskKeyRingTestHelper, TestSettings, TimeProvider}
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

trait RPCMockState
    extends AnyWordSpec
    with TestSettings
    with DiskKeyRingTestHelper
    with ValidTransactionGenerators
    with ValidBlockchainGenerator
    with ScalatestRouteTest
    with BeforeAndAfterAll
    with ScalaFutures {

  type BSI = BifrostSyncInfo
  type PMOD = Block
  type HIS = History
  type MP = MemPool
  type ST = State

  implicit val timeout: Timeout = Timeout(10.seconds)

  implicit protected val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(5.seconds)

  protected def blockchainGen: Byte => Gen[NonEmptyChain[Block]] =
    (length: Byte) => validChainFromGenesis(keyRingCurve25519, protocolVersioner)(length)

  // TODO Fails when using rpcSettings
  override def createActorSystem(): ActorSystem = ActorSystem(settings.network.agentName)

  // Create Bifrost singleton actors

  // NOTE: Some of these actors are TestActors in order to access the underlying instance so that we can manipulate
  //       the state of the underlying instance while testing. Use with caution
  protected var keyManagerRef: TestActorRef[KeyManager] = _
  protected var forgerRef: akka.actor.typed.ActorRef[Forger.ReceivableMessage] = _

  protected var consensusHolderRef: akka.actor.typed.ActorRef[NxtConsensus.ReceivableMessage] = _
  protected var consensusInterface: ConsensusInterface = _
  protected var nodeViewHolderRef: akka.actor.typed.ActorRef[NodeViewHolder.ReceivableMessage] = _

  implicit protected var timeProvider: TimeProvider = _

  var rpcServer: ToplRpcServer = _

  var route: Route = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    timeProvider = new NetworkTimeProvider(settings.ntp)(system.toTyped)

    keyManagerRef = TestActorRef(
      new KeyManager(settings)(appContext.networkType.netPrefix)
    )

    keyManagerRef.underlyingActor.context.become(
      keyManagerRef.underlyingActor.receive(keyRingCurve25519, Some(keyRingCurve25519.addresses.head))
    )

    consensusHolderRef = system.toTyped.systemActorOf(
      NxtConsensus(
        settings,
        InMemoryKeyValueStore.empty()
      ),
      NxtConsensus.actorName
    )

    consensusInterface = new ActorConsensusInterface(consensusHolderRef)(system.toTyped, 10.seconds)

    nodeViewHolderRef = system.toTyped.systemActorOf(
      NodeViewHolder(
        settings,
        consensusInterface,
        () =>
          NodeView.persistent(
            settings,
            consensusInterface,
            () => Future.successful(StartupKeyView(keyRingCurve25519.addresses, Some(keyRingCurve25519.addresses.head)))
          )(system.toTyped, implicitly, networkPrefix, protocolVersioner)
      ),
      NodeViewHolder.ActorName
    )

    forgerRef = system.toTyped.systemActorOf(
      Forger.behavior(
        settings.forging.blockGenerationDelay,
        settings.forging.minTransactionFee,
        settings.forging.forgeOnStartup,
        () => (keyManagerRef ? KeyManager.ReceivableMessages.GetKeyView).mapTo[KeyView],
        new ActorNodeViewHolderInterface(nodeViewHolderRef)(system.toTyped, implicitly[Timeout]),
        new ActorConsensusInterface(consensusHolderRef)(system.toTyped, 10.seconds)
      ),
      Forger.ActorName
    )

    rpcServer = {
      implicit val typedSystem: akka.actor.typed.ActorSystem[_] = system.toTyped
      val forgerInterface = new ActorForgerInterface(forgerRef)
      val keyManagerInterface = new ActorKeyManagerInterface(keyManagerRef)
      val nodeViewHolderInterface =
        new ActorNodeViewHolderInterface(nodeViewHolderRef)
      val consensusInterface = new ActorConsensusInterface(consensusHolderRef)

      import co.topl.rpc.handlers._
      new ToplRpcServer(
        ToplRpcHandlers(
          new DebugRpcHandlerImpls(nodeViewHolderInterface, keyManagerInterface),
          new UtilsRpcHandlerImpls,
          new NodeViewRpcHandlerImpls(settings.rpcApi, appContext, consensusInterface, nodeViewHolderInterface),
          new TransactionRpcHandlerImpls(nodeViewHolderInterface),
          new AdminRpcHandlerImpls(forgerInterface, keyManagerInterface, nodeViewHolderInterface)
        ),
        appContext
      )
    }

    val httpService = HttpService(settings.rpcApi, rpcServer)
    route = httpService.compositeRoute
  }

  def httpPOST(jsonRequest: ByteString): HttpRequest =
    HttpRequest(
      HttpMethods.POST,
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    ).withHeaders(RawHeader("x-api-key", "test_key"))

  protected def view(): NodeView =
    TestableNodeViewHolder.nodeViewOf(nodeViewHolderRef)(system.toTyped)
}
