package dev.kdrant.transport.grpc

import dev.kdrant.QdrantClient
import dev.kdrant.testkit.DegradedClusterContract
import dev.kdrant.testkit.QdrantCluster

/** The degraded-cluster contract over the gRPC engine: the same states, reported the same way. */
class GrpcDegradedClusterIntegrationTest : DegradedClusterContract() {
    override fun connect(cluster: QdrantCluster): QdrantClient =
        KdrantGrpc(host = cluster.host, port = cluster.grpcPort)
}
