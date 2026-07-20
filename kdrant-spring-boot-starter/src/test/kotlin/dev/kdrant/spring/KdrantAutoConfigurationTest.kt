package dev.kdrant.spring

import dev.kdrant.QdrantClient
import dev.kdrant.transport.rest.Kdrant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class KdrantAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KdrantAutoConfiguration::class.java))

    @Test
    fun `auto-configures a QdrantClient bean by default`() {
        runner.run { context ->
            assertEquals(1, context.getBeanNamesForType(QdrantClient::class.java).size)
        }
    }

    @Test
    fun `binds kdrant properties`() {
        runner
            .withPropertyValues("kdrant.host=example.internal", "kdrant.port=7000", "kdrant.max-retries=1")
            .run { context ->
                val props = context.getBean(KdrantProperties::class.java)
                assertEquals("example.internal", props.host)
                assertEquals(7000, props.port)
                assertEquals(1, props.maxRetries)
            }
    }

    @Test
    fun `backs off when a QdrantClient bean is already defined`() {
        runner
            .withBean(QdrantClient::class.java, { Kdrant(host = "custom") })
            .run { context ->
                assertEquals(1, context.getBeanNamesForType(QdrantClient::class.java).size)
            }
    }
}
