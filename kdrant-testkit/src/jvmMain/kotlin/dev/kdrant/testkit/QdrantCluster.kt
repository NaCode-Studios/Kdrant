package dev.kdrant.testkit

import org.testcontainers.containers.Network
import org.testcontainers.qdrant.QdrantContainer
import java.io.Closeable

/**
 * A two-node Qdrant cluster in Docker, so the states an operator meets can be reached from a test.
 *
 * The sharding tests that came before this asserted request bodies against a mock, which proves the
 * client can *ask* about a cluster and says nothing about what it does when one is unwell. Every
 * interesting behaviour here needs a real second node: a shard has to have somewhere else to live
 * before it can become unreachable.
 *
 * Consensus is why the two nodes are not symmetric. The first is the bootstrap peer and the second
 * joins it by its network alias, which only resolves inside the shared Docker network — so the peer
 * URIs are internal names while the client talks to the mapped ports from outside.
 *
 * Closing it stops both nodes and removes the network. [Closeable] rather than `AutoCloseable` so a
 * JUnit `@AfterAll` can use it either way.
 */
public class QdrantCluster(
    image: String = System.getenv("QDRANT_IMAGE") ?: DEFAULT_IMAGE,
) : Closeable {

    private val network: Network = Network.newNetwork()

    /** The bootstrap peer. Every client in these tests talks to this one unless a test says otherwise. */
    public val first: QdrantContainer = QdrantContainer(image)
        .withNetwork(network)
        .withNetworkAliases(FIRST_ALIAS)
        .withEnv("QDRANT__CLUSTER__ENABLED", "true")
        .withCommand("./qdrant", "--uri", "http://$FIRST_ALIAS:6335")

    /** The joining peer. Stopping this one is how a shard loses its only replica. */
    public val second: QdrantContainer = QdrantContainer(image)
        .withNetwork(network)
        .withNetworkAliases(SECOND_ALIAS)
        .withEnv("QDRANT__CLUSTER__ENABLED", "true")
        .withCommand(
            "./qdrant",
            "--bootstrap",
            "http://$FIRST_ALIAS:6335",
            "--uri",
            "http://$SECOND_ALIAS:6335",
        )

    /** Starts both nodes, in order: the second cannot join a peer that is not listening yet. */
    public fun start(): QdrantCluster {
        first.start()
        second.start()
        return this
    }

    /** REST port of [first], as reachable from the test process. */
    public val restPort: Int get() = first.getMappedPort(REST_PORT)

    /** gRPC port of [first], as reachable from the test process. */
    public val grpcPort: Int get() = first.grpcPort

    /** Host of [first], as reachable from the test process. */
    public val host: String get() = first.host

    /**
     * Stops [second] without removing it, leaving any shard that lived only there unreachable.
     *
     * Stopping rather than pausing: a paused container still holds its TCP connections open, so the
     * surviving peer waits on them instead of deciding the node is gone, and the test would measure a
     * timeout rather than the behaviour it came for.
     */
    public fun stopSecond() {
        if (second.isRunning) second.stop()
    }

    override fun close() {
        runCatching { if (second.isRunning) second.stop() }
        runCatching { if (first.isRunning) first.stop() }
        runCatching { network.close() }
    }

    public companion object {
        /** Pinned to the same image the rest of the suite runs against; `QDRANT_IMAGE` overrides it. */
        public const val DEFAULT_IMAGE: String = "qdrant/qdrant:v1.18.2"
        private const val FIRST_ALIAS = "qdrant-1"
        private const val SECOND_ALIAS = "qdrant-2"
        private const val REST_PORT = 6333
    }
}
