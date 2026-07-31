package dev.kdrant.transport.grpc

import dev.kdrant.QdrantClient
import dev.kdrant.testkit.QdrantClientContract
import org.testcontainers.qdrant.QdrantContainer

/**
 * The shared client contract, run over the gRPC engine. Same file, same server, different protocol —
 * which is the whole point of extracting it.
 *
 * The port is the one difference the subclass has to know about: Qdrant serves gRPC on 6334 and the
 * container maps both.
 */
class GrpcClientContractIntegrationTest : QdrantClientContract() {

    override fun connect(container: QdrantContainer): QdrantClient =
        KdrantGrpc(host = container.host, port = container.grpcPort)
}
