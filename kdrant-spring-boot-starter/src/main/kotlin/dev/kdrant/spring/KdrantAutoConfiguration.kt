package dev.kdrant.spring

import dev.kdrant.QdrantClient
import dev.kdrant.transport.rest.Kdrant
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import kotlin.time.toKotlinDuration

/**
 * Spring Boot auto-configuration that exposes a [QdrantClient] built from [KdrantProperties].
 *
 * The bean is created only when the application does not already define its own `QdrantClient`
 * (`@ConditionalOnMissingBean`), and is closed with the application context (`destroyMethod = "close"`).
 */
@AutoConfiguration
@EnableConfigurationProperties(KdrantProperties::class)
public class KdrantAutoConfiguration {

    /** The auto-configured client. Define your own `QdrantClient` bean to override it. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public fun qdrantClient(properties: KdrantProperties): QdrantClient =
        Kdrant(
            host = properties.host,
            port = properties.port,
            upsertBatchSize = properties.upsertBatchSize,
        ) {
            apiKey = properties.apiKey?.takeIf { it.isNotBlank() }
            useTls = properties.useTls
            requestTimeout = properties.requestTimeout.toKotlinDuration()
            maxRetries = properties.maxRetries
        }
}
