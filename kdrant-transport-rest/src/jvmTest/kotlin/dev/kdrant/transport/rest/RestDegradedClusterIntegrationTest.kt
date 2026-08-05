package dev.kdrant.transport.rest

import dev.kdrant.QdrantClient
import dev.kdrant.testkit.DegradedClusterContract
import dev.kdrant.testkit.QdrantCluster

/** The degraded-cluster contract over the REST engine. */
class RestDegradedClusterIntegrationTest : DegradedClusterContract() {
    override fun connect(cluster: QdrantCluster): QdrantClient =
        Kdrant(host = cluster.host, port = cluster.restPort)
}
