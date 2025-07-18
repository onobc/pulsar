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
package org.apache.pulsar.client.tutorial;

import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

public class SampleConsumer {
    public static void main(String[] args) throws PulsarClientException, InterruptedException {

        PulsarClient pulsarClient = PulsarClient.builder().serviceUrl("pulsar://localhost:6650").build();

        Consumer<byte[]> consumer = pulsarClient.newConsumer() //
                .topic("persistent://my-tenant/my-ns/my-topic") //
                .subscriptionName("my-subscription-name").subscribe();

        Message<byte[]> msg = null;

        for (int i = 0; i < 100; i++) {
            msg = consumer.receive();
            // do something
            System.out.println("Received: " + new String(msg.getData()));
        }

        // Acknowledge the consumption of all messages at once
        try {
            consumer.acknowledgeCumulative(msg);
        } catch (Exception e) {
            consumer.reconsumeLater(msg, 10, TimeUnit.SECONDS);
        }

        pulsarClient.close();
    }
}
