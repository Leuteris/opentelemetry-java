/*
 * Copyright 2020, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentelemetry.exporters.otlp;

import io.grpc.ManagedChannel;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.sdk.common.export.ConfigBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;

/** Exports spans using OTLP via gRPC, using OpenTelemetry's protobuf model. */
@ThreadSafe
public final class OtlpGrpcSpanExporter implements SpanExporter {
  private static final Logger logger = Logger.getLogger(OtlpGrpcSpanExporter.class.getName());

  private final TraceServiceGrpc.TraceServiceBlockingStub blockingStub;
  private final ManagedChannel managedChannel;
  private final long deadlineMs;

  /**
   * Creates a new OTLP gRPC Span Reporter with the given name, using the given channel.
   *
   * @param channel the channel to use when communicating with the OpenTelemetry Collector.
   * @param deadlineMs max waiting time for the collector to process each span batch. When set to 0
   *     or to a negative value, the exporter will wait indefinitely.
   */
  private OtlpGrpcSpanExporter(ManagedChannel channel, long deadlineMs) {
    this.managedChannel = channel;
    this.blockingStub = TraceServiceGrpc.newBlockingStub(channel);
    this.deadlineMs = deadlineMs;
  }

  /**
   * Submits all the given spans in a single batch to the OpenTelemetry collector.
   *
   * @param spans the list of sampled Spans to be exported.
   * @return the result of the operation
   */
  @Override
  public ResultCode export(Collection<SpanData> spans) {
    ExportTraceServiceRequest exportTraceServiceRequest =
        ExportTraceServiceRequest.newBuilder()
            .addAllResourceSpans(SpanAdapter.toProtoResourceSpans(spans))
            .build();

    try {
      TraceServiceGrpc.TraceServiceBlockingStub stub = this.blockingStub;
      if (deadlineMs > 0) {
        stub = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
      }

      // for now, there's nothing to check in the response object
      // noinspection ResultOfMethodCallIgnored
      stub.export(exportTraceServiceRequest);
      return ResultCode.SUCCESS;
    } catch (Throwable e) {
      return ResultCode.FAILURE;
    }
  }

  /**
   * The OTLP exporter does not batch spans, so this method will immediately return with success.
   *
   * @return always Success
   */
  @Override
  public ResultCode flush() {
    return ResultCode.SUCCESS;
  }

  /**
   * Returns a new builder instance for this exporter.
   *
   * @return a new builder instance for this exporter.
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Returns a new {@link OtlpGrpcSpanExporter} reading the configuration values from the
   * environment and from system properties. System properties override values defined in the
   * environment. If a configuration value is missing, it uses the default value.
   *
   * @return a new {@link OtlpGrpcSpanExporter} instance.
   * @since 0.5.0
   */
  public static OtlpGrpcSpanExporter getDefault() {
    return newBuilder().readEnvironment().readSystemProperties().build();
  }

  /**
   * Initiates an orderly shutdown in which preexisting calls continue but new calls are immediately
   * cancelled. The channel is forcefully closed after a timeout.
   */
  @Override
  public void shutdown() {
    try {
      managedChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      logger.log(Level.WARNING, "Failed to shutdown the gRPC channel", e);
    }
  }

  /** Builder utility for this exporter. */
  public static class Builder extends ConfigBuilder<Builder> {
    private static final String KEY_SPAN_TIMEOUT = "otel.otlp.span.timeout";
    private ManagedChannel channel;
    private long deadlineMs = 1_000; // 1 second

    /**
     * Sets the managed chanel to use when communicating with the backend. Required.
     *
     * @param channel the channel to use
     * @return this builder's instance
     */
    public Builder setChannel(ManagedChannel channel) {
      this.channel = channel;
      return this;
    }

    /**
     * Sets the max waiting time for the collector to process each span batch. Optional.
     *
     * @param deadlineMs the max waiting time
     * @return this builder's instance
     */
    public Builder setDeadlineMs(long deadlineMs) {
      this.deadlineMs = deadlineMs;
      return this;
    }

    /**
     * Constructs a new instance of the exporter based on the builder's values.
     *
     * @return a new exporter's instance
     */
    public OtlpGrpcSpanExporter build() {
      return new OtlpGrpcSpanExporter(channel, deadlineMs);
    }

    private Builder() {}

    /**
     * Sets the configuration values from the given configuration map for only the available keys.
     * This method looks for the following keys:
     *
     * <ul>
     *   <li>{@code otel.otlp.span.timeout}: to set the max waiting time for the collector to
     *       process each span batch.
     * </ul>
     *
     * @param configMap {@link Map} holding the configuration values.
     * @return this.
     */
    @Override
    protected Builder fromConfigMap(
        Map<String, String> configMap, NamingConvention namingConvention) {
      configMap = namingConvention.normalize(configMap);
      Long value = getLongProperty(KEY_SPAN_TIMEOUT, configMap);
      if (value != null) {
        this.setDeadlineMs(value);
      }
      return this;
    }

    /**
     * Sets the configuration values from the given properties object for only the available keys.
     * This method looks for the following keys:
     *
     * <ul>
     *   <li>{@code otel.otlp.span.timeout}: to set the max waiting time for the collector to
     *       process each span batch.
     * </ul>
     *
     * @param properties {@link Properties} holding the configuration values.
     * @return this.
     */
    @Override
    public Builder readProperties(Properties properties) {
      return super.readProperties(properties);
    }

    /**
     * Sets the configuration values from environment variables for only the available keys. This
     * method looks for the following keys:
     *
     * <ul>
     *   <li>{@code OTEL_OTLP_SPAN_TIMEOUT}: to set the max waiting time for the collector to
     *       process each span batch.
     * </ul>
     *
     * @return this.
     */
    @Override
    public Builder readEnvironment() {
      return super.readEnvironment();
    }

    /**
     * Sets the configuration values from system properties for only the available keys. This method
     * looks for the following keys:
     *
     * <ul>
     *   <li>{@code otel.otlp.span.timeout}: to set the max waiting time for the collector to
     *       process each span batch.
     * </ul>
     *
     * @return this.
     */
    @Override
    public Builder readSystemProperties() {
      return super.readSystemProperties();
    }
  }
}
