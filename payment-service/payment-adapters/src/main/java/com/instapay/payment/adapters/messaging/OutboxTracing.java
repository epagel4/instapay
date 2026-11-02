package com.instapay.payment.adapters.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries the trace across the outbox: the writer stores the current traceparent with the row, the
 * relay (another thread, seconds later) publishes under a span that continues it. Kafka template
 * observation is off in this service because it would parent to the relay's poll instead.
 */
@Component
final class OutboxTracing {

  static final String TRACEPARENT = "traceparent";

  private final Tracer tracer;
  private final Propagator propagator;

  OutboxTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
  }

  /** traceparent of the current span, or null when not tracing. */
  String currentTraceParent() {
    Span span = tracer.currentSpan();
    if (span == null) {
      return null;
    }
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(span.context(), carrier, Map::put);
    return carrier.get(TRACEPARENT);
  }

  /** Starts a producer span continuing {@code traceParent} and injects it into the headers. */
  Span startPublish(String traceParent, String topic, Headers headers) {
    Span.Builder builder =
        traceParent == null
            ? tracer.spanBuilder().setNoParent()
            : propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get);
    Span span =
        builder
            .name(topic + " publish")
            .kind(Span.Kind.PRODUCER)
            .tag("messaging.destination.name", topic)
            .start();
    propagator.inject(
        span.context(), headers, (h, k, v) -> h.add(k, v.getBytes(StandardCharsets.UTF_8)));
    return span;
  }
}
