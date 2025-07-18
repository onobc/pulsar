/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.tests.integration.io;

import dev.failsafe.RetryPolicy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.schema.KeyValueSchemaImpl;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.apache.pulsar.tests.integration.io.sinks.SinkTester;
import org.apache.pulsar.tests.integration.topologies.PulsarCluster;

@Slf4j
public abstract class PulsarIOTestRunner {
    static final long MB = 1048576L;
    public static final long RUNTIME_INSTANCE_RAM_BYTES = 128 * MB;
    final Duration oneMinute = Duration.ofMinutes(1);
    final Duration tenSeconds = Duration.ofSeconds(10);

    protected final RetryPolicy<?> statusRetryPolicy = RetryPolicy.builder()
            .withMaxDuration(oneMinute)
            .withDelay(tenSeconds)
            .onRetry(e -> log.error("Retry ... "))
            .build();

    protected PulsarCluster pulsarCluster;
    protected String functionRuntimeType;

    protected PulsarIOTestRunner(PulsarCluster cluster, String functionRuntimeType) {
      this.pulsarCluster = cluster;
      this.functionRuntimeType = functionRuntimeType;
    }

    protected Schema<?> getSchema(boolean jsonWithEnvelope) {
        if (jsonWithEnvelope) {
            return KeyValueSchemaImpl.kvBytes();
        } else {
            return KeyValueSchemaImpl.of(Schema.AUTO_CONSUME(), Schema.AUTO_CONSUME(), KeyValueEncodingType.SEPARATED);
        }
    }

    @SuppressWarnings("try")
    protected <T> void ensureSubscriptionCreated(String inputTopicName,
                                                      String subscriptionName,
                                                      Schema<T> inputTopicSchema)
            throws Exception {
        // ensure the function subscription exists before we start producing messages
        try (PulsarClient client = PulsarClient.builder()
            .serviceUrl(pulsarCluster.getPlainTextServiceUrl())
            .build()) {
            try (Consumer<T> ignored = client.newConsumer(inputTopicSchema)
                .topic(inputTopicName)
                .subscriptionType(SubscriptionType.Shared)
                .subscriptionName(subscriptionName)
                .subscribe()) {
            }
        }
    }

    protected Map<String, String> produceMessagesToInputTopic(String inputTopicName,
                                                              int numMessages, SinkTester<?> tester) throws Exception {

        @Cleanup
        PulsarClient client = PulsarClient.builder()
                .serviceUrl(pulsarCluster.getPlainTextServiceUrl())
                .build();
        LinkedHashMap<String, String> kvs = new LinkedHashMap<>();
        tester.produceMessage(numMessages, client, inputTopicName, kvs);
        return kvs;
    }
}
