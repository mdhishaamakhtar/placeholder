package dev.placeholder.server.infra

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/** One registry per process, scraped at /metrics. */
class Metrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    fun scrape(): String = registry.scrape()
}
