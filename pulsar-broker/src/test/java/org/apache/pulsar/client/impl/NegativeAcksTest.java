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
package org.apache.pulsar.client.impl;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.broker.BrokerTestUtil;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerConsumerBase;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.api.TopicMessageId;
import org.awaitility.Awaitility;
import org.awaitility.reflect.WhiteboxImpl;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@Slf4j
@Test(groups = "broker-impl")
public class NegativeAcksTest extends ProducerConsumerBase {

    @Override
    @BeforeClass
    public void setup() throws Exception {
        super.internalSetup();
        super.producerBaseSetup();
    }

    @Override
    @AfterClass(alwaysRun = true)
    public void cleanup() throws Exception {
        super.internalCleanup();
    }

    @DataProvider(name = "variations")
    public static Object[][] variations() {
        return new Object[][] {
                // batching / partitions / subscription-type / redelivery-delay-ms / ack-timeout
                { false, false, SubscriptionType.Shared, 100, 0 },
                { false, false, SubscriptionType.Failover, 100, 0 },
                { false, true, SubscriptionType.Shared, 100, 0 },
                { false, true, SubscriptionType.Failover, 100, 0 },
                { true, false, SubscriptionType.Shared, 100, 0 },
                { true, false, SubscriptionType.Failover, 100, 0 },
                { true, true, SubscriptionType.Shared, 100, 0 },
                { true, true, SubscriptionType.Failover, 100, 0 },

                { false, false, SubscriptionType.Shared, 0, 0 },
                { false, false, SubscriptionType.Failover, 0, 0 },
                { false, true, SubscriptionType.Shared, 0, 0 },
                { false, true, SubscriptionType.Failover, 0, 0 },
                { true, false, SubscriptionType.Shared, 0, 0 },
                { true, false, SubscriptionType.Failover, 0, 0 },
                { true, true, SubscriptionType.Shared, 0, 0 },
                { true, true, SubscriptionType.Failover, 0, 0 },

                { false, false, SubscriptionType.Shared, 100, 1000 },
                { false, false, SubscriptionType.Failover, 100, 1000 },
                { false, true, SubscriptionType.Shared, 100, 1000 },
                { false, true, SubscriptionType.Failover, 100, 1000 },
                { true, false, SubscriptionType.Shared, 100, 1000 },
                { true, false, SubscriptionType.Failover, 100, 1000 },
                { true, true, SubscriptionType.Shared, 100, 1000 },
                { true, true, SubscriptionType.Failover, 100, 1000 },

                { false, false, SubscriptionType.Shared, 0, 1000 },
                { false, false, SubscriptionType.Failover, 0, 1000 },
                { false, true, SubscriptionType.Shared, 0, 1000 },
                { false, true, SubscriptionType.Failover, 0, 1000 },
                { true, false, SubscriptionType.Shared, 0, 1000 },
                { true, false, SubscriptionType.Failover, 0, 1000 },
                { true, true, SubscriptionType.Shared, 0, 1000 },
                { true, true, SubscriptionType.Failover, 0, 1000 },
        };
    }

    @Test(dataProvider = "variations")
    public void testNegativeAcks(boolean batching, boolean usePartitions, SubscriptionType subscriptionType,
            int negAcksDelayMillis, int ackTimeout)
            throws Exception {
        log.info("Test negative acks batching={} partitions={} subType={} negAckDelayMs={}", batching, usePartitions,
                subscriptionType, negAcksDelayMillis);
        String topic = BrokerTestUtil.newUniqueName("testNegativeAcks");
        if (usePartitions) {
            admin.topics().createPartitionedTopic(topic, 2);
        }

        @Cleanup
        Consumer<String> consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("sub1")
                .acknowledgmentGroupTime(0, TimeUnit.SECONDS)
                .subscriptionType(subscriptionType)
                .negativeAckRedeliveryDelay(negAcksDelayMillis, TimeUnit.MILLISECONDS)
                .ackTimeout(ackTimeout, TimeUnit.MILLISECONDS)
                .subscribe();

        @Cleanup
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topic)
                .enableBatching(batching)
                .create();

        Set<String> sentMessages = new HashSet<>();

        final int num = 10;
        for (int i = 0; i < num * 2; i++) {
            String value = "test-" + i;
            producer.sendAsync(value);
            sentMessages.add(value);
        }
        producer.flush();

        for (int i = 0; i < num; i++) {
            Message<String> msg = consumer.receive();
            consumer.negativeAcknowledge(msg);
        }

        for (int i = 0; i < num; i++) {
            Message<String> msg = consumer.receive();
            consumer.negativeAcknowledge(msg.getMessageId());
        }

        assertTrue(consumer instanceof ConsumerBase<String>);
        assertEquals(((ConsumerBase<String>) consumer).getUnAckedMessageTracker().size(), 0);

        Set<String> receivedMessages = new HashSet<>();

        // All the messages should be received again
        for (int i = 0; i < num * 2; i++) {
            Message<String> msg = consumer.receive();
            receivedMessages.add(msg.getValue());
            consumer.acknowledge(msg);
        }

        assertEquals(receivedMessages, sentMessages);

        // There should be no more messages
        assertNull(consumer.receive(100, TimeUnit.MILLISECONDS));
        consumer.close();
        producer.close();
    }

    @DataProvider(name = "variationsBackoff")
    public static Object[][] variationsBackoff() {
        return new Object[][] {
                // batching / partitions / subscription-type / min-nack-time-ms/ max-nack-time-ms / ack-timeout
                { false, false, SubscriptionType.Shared, 100, 1000 },
                { false, false, SubscriptionType.Failover, 100, 1000 },
                { false, true, SubscriptionType.Shared, 100, 1000 },
                { false, true, SubscriptionType.Failover, 100, 1000 },
                { true, false, SubscriptionType.Shared, 100, 1000 },
                { true, false, SubscriptionType.Failover, 100, 1000 },
                { true, true, SubscriptionType.Shared, 100, 1000 },
                { true, true, SubscriptionType.Failover, 100, 1000 },

                { false, false, SubscriptionType.Shared, 0, 1000 },
                { false, false, SubscriptionType.Failover, 0, 1000 },
                { false, true, SubscriptionType.Shared, 0, 1000 },
                { false, true, SubscriptionType.Failover, 0, 1000 },
                { true, false, SubscriptionType.Shared, 0, 1000 },
                { true, false, SubscriptionType.Failover, 0, 1000 },
                { true, true, SubscriptionType.Shared, 0, 1000 },
                { true, true, SubscriptionType.Failover, 0, 1000 },

                { false, false, SubscriptionType.Shared, 100, 1000 },
                { false, false, SubscriptionType.Failover, 100, 1000 },
                { false, true, SubscriptionType.Shared, 100, 1000 },
                { false, true, SubscriptionType.Failover, 100, 1000 },
                { true, false, SubscriptionType.Shared, 100, 1000 },
                { true, false, SubscriptionType.Failover, 100, 1000 },
                { true, true, SubscriptionType.Shared, 100, 1000 },
                { true, true, SubscriptionType.Failover, 100, 1000 },

                { false, false, SubscriptionType.Shared, 0, 1000 },
                { false, false, SubscriptionType.Failover, 0, 1000 },
                { false, true, SubscriptionType.Shared, 0, 1000 },
                { false, true, SubscriptionType.Failover, 0, 1000 },
                { true, false, SubscriptionType.Shared, 0, 1000 },
                { true, false, SubscriptionType.Failover, 0, 1000 },
                { true, true, SubscriptionType.Shared, 0, 1000 },
                { true, true, SubscriptionType.Failover, 0, 1000 },
        };
    }

    @Test(dataProvider = "variationsBackoff")
    public void testNegativeAcksWithBackoff(boolean batching, boolean usePartitions, SubscriptionType subscriptionType,
            int minNackTimeMs, int maxNackTimeMs)
            throws Exception {
        log.info("Test negative acks with back off batching={} partitions={} subType={} minNackTimeMs={}, "
                        + "maxNackTimeMs={}", batching, usePartitions, subscriptionType, minNackTimeMs, maxNackTimeMs);
        String topic = BrokerTestUtil.newUniqueName("testNegativeAcksWithBackoff");

        MultiplierRedeliveryBackoff backoff = MultiplierRedeliveryBackoff.builder()
                .minDelayMs(minNackTimeMs)
                .maxDelayMs(maxNackTimeMs)
                .build();

        @Cleanup
        Consumer<String> consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("sub1")
                .acknowledgmentGroupTime(0, TimeUnit.SECONDS)
                .subscriptionType(subscriptionType)
                .negativeAckRedeliveryBackoff(backoff)
                .subscribe();

        @Cleanup
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topic)
                .enableBatching(batching)
                .create();

        Set<String> sentMessages = new HashSet<>();

        final int num = 10;
        for (int i = 0; i < num; i++) {
            String value = "test-" + i;
            producer.sendAsync(value);
            sentMessages.add(value);
        }
        producer.flush();

        final int redeliverCount = 5;
        long firstReceivedAt = System.currentTimeMillis();
        long expectedTotalRedeliveryDelay = 0;
        for (int i = 0; i < redeliverCount; i++) {
            Message<String> msg = null;
            for (int j = 0; j < num; j++) {
                msg = consumer.receive();
                log.info("Received message {}", msg.getValue());
                if (!batching) {
                    consumer.negativeAcknowledge(msg);
                }
            }
            if (batching) {
                // for batching, we only need to nack one message in the batch to trigger redelivery
                consumer.negativeAcknowledge(msg);
            }
            expectedTotalRedeliveryDelay += backoff.next(i);
        }

        Set<String> receivedMessages = new HashSet<>();

        // All the messages should be received again
        for (int i = 0; i < num; i++) {
            Message<String> msg = consumer.receive();
            receivedMessages.add(msg.getValue());
            consumer.acknowledge(msg);
        }
        long receivedAfterRedeliveryAt = System.currentTimeMillis();
        log.info("Total redelivery delay: {} ms", receivedAfterRedeliveryAt - firstReceivedAt);
        assertEquals(receivedMessages, sentMessages);

        if (SubscriptionType.Shared == subscriptionType) {
            log.info("Total expected redelivery delay {} ms", expectedTotalRedeliveryDelay);
            assertTrue(receivedAfterRedeliveryAt - firstReceivedAt >= expectedTotalRedeliveryDelay);
        }

        // There should be no more messages
        assertNull(consumer.receive(100, TimeUnit.MILLISECONDS));
        consumer.close();
        producer.close();
    }

    @Test(timeOut = 10000)
    public void testNegativeAcksDeleteFromUnackedTracker() throws Exception {
        String topic = BrokerTestUtil.newUniqueName("testNegativeAcksDeleteFromUnackedTracker");
        @Cleanup
        ConsumerImpl<String> consumer = (ConsumerImpl<String>) pulsarClient.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("sub1")
                .acknowledgmentGroupTime(0, TimeUnit.SECONDS)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(100, TimeUnit.SECONDS)
                .negativeAckRedeliveryDelay(100, TimeUnit.SECONDS)
                .subscribe();

        MessageId messageId = new MessageIdImpl(3, 1, 0);
        TopicMessageId topicMessageId = TopicMessageId.create("topic-1", messageId);
        BatchMessageIdImpl batchMessageId = new BatchMessageIdImpl(3, 1, 0, 0);
        BatchMessageIdImpl batchMessageId2 = new BatchMessageIdImpl(3, 1, 0, 1);
        BatchMessageIdImpl batchMessageId3 = new BatchMessageIdImpl(3, 1, 0, 2);

        UnAckedMessageTracker unAckedMessageTracker = consumer.getUnAckedMessageTracker();
        unAckedMessageTracker.add(topicMessageId);

        // negative topic message id
        consumer.negativeAcknowledge(topicMessageId);
        NegativeAcksTracker negativeAcksTracker = consumer.getNegativeAcksTracker();
        assertEquals(negativeAcksTracker.getNackedMessagesCount(), 1L);
        assertEquals(unAckedMessageTracker.size(), 0);
        negativeAcksTracker.close();
        // negative batch message id
        unAckedMessageTracker.add(messageId);
        consumer.negativeAcknowledge(batchMessageId);
        consumer.negativeAcknowledge(batchMessageId2);
        consumer.negativeAcknowledge(batchMessageId3);
        assertEquals(negativeAcksTracker.getNackedMessagesCount(), 1L);
        assertEquals(unAckedMessageTracker.size(), 0);
        negativeAcksTracker.close();
    }

    /**
     * If we nack multiple messages in the same batch with different redelivery delays, the messages should be
     * redelivered with the correct delay. However, all messages are redelivered at the same time.
     * @throws Exception
     */
    @Test
    public void testNegativeAcksWithBatch() throws Exception {
        cleanup();
        conf.setAcknowledgmentAtBatchIndexLevelEnabled(true);
        setup();
        String topic = BrokerTestUtil.newUniqueName("testNegativeAcksWithBatch");

        @Cleanup
        Consumer<String> consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("sub1")
                .acknowledgmentGroupTime(0, TimeUnit.SECONDS)
                .subscriptionType(SubscriptionType.Shared)
                .enableBatchIndexAcknowledgment(true)
                .negativeAckRedeliveryDelay(3, TimeUnit.SECONDS)
                .subscribe();

        @Cleanup
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topic)
                .enableBatching(true)
                .batchingMaxPublishDelay(1, TimeUnit.HOURS)
                .batchingMaxMessages(2)
                .create();
        // send two messages in the same batch
        producer.sendAsync("test-0");
        producer.sendAsync("test-1");
        producer.flush();

        // negative ack the first message
        consumer.negativeAcknowledge(consumer.receive());
        // wait for 2s, negative ack the second message
        Thread.sleep(2000);
        consumer.negativeAcknowledge(consumer.receive());

        // now 2s has passed, the first message should be redelivered 1s later.
        Message<String> msg1 = consumer.receive(2, TimeUnit.SECONDS);
        assertNotNull(msg1);
    }

    @Test
    public void testNegativeAcksWithBatchAckEnabled() throws Exception {
        cleanup();
        setup();
        String topic = BrokerTestUtil.newUniqueName("testNegativeAcksWithBatchAckEnabled");

        @Cleanup
        Consumer<String> consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("sub1")
                .acknowledgmentGroupTime(0, TimeUnit.SECONDS)
                .subscriptionType(SubscriptionType.Shared)
                .negativeAckRedeliveryDelay(1, TimeUnit.SECONDS)
                .subscribe();

        @Cleanup
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topic)
                .create();

        Set<String> sentMessages = new HashSet<>();
        final int num = 10;
        for (int i = 0; i < num; i++) {
            String value = "test-" + i;
            producer.sendAsync(value);
            sentMessages.add(value);
        }
        producer.flush();

        for (int i = 0; i < num; i++) {
            Message<String> msg = consumer.receive();
            consumer.negativeAcknowledge(msg);
        }

        Set<String> receivedMessages = new HashSet<>();

        // All the messages should be received again
        for (int i = 0; i < num; i++) {
            Message<String> msg = consumer.receive();
            receivedMessages.add(msg.getValue());
            consumer.acknowledge(msg);
        }

        assertEquals(receivedMessages, sentMessages);
        // There should be no more messages
        assertNull(consumer.receive(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testFailoverConsumerBatchCumulateAck() throws Exception {
        final String topic = BrokerTestUtil.newUniqueName("my-topic");
        admin.topics().createPartitionedTopic(topic, 2);

        @Cleanup
        Consumer<Integer> consumer = pulsarClient.newConsumer(Schema.INT32)
                .topic(topic)
                .subscriptionName("sub")
                .subscriptionType(SubscriptionType.Failover)
                .acknowledgmentGroupTime(100, TimeUnit.MILLISECONDS)
                .receiverQueueSize(10)
                .subscribe();

        @Cleanup
        Producer<Integer> producer = pulsarClient.newProducer(Schema.INT32)
                .topic(topic)
                .batchingMaxMessages(10)
                .batchingMaxPublishDelay(3, TimeUnit.SECONDS)
                .blockIfQueueFull(true)
                .create();

        int count = 0;
        Set<Integer> datas = new HashSet<>();
        CountDownLatch producerLatch = new CountDownLatch(10);
        while (count < 10) {
            datas.add(count);
            producer.sendAsync(count).whenComplete((m, e) -> {
                producerLatch.countDown();
            });
            count++;
        }
        producerLatch.await();
        CountDownLatch consumerLatch = new CountDownLatch(1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                consumer.receiveAsync()
                        .thenCompose(m -> {
                            log.info("received one msg : {}", m.getMessageId());
                            datas.remove(m.getValue());
                            return consumer.acknowledgeCumulativeAsync(m);
                        })
                        .thenAccept(ignore -> {
                            try {
                                Thread.sleep(500);
                                consumer.redeliverUnacknowledgedMessages();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .whenComplete((r, e) -> {
                            consumerLatch.countDown();
                        });
            }
        }).start();
        consumerLatch.await();
        Thread.sleep(500);
        count = 0;
        while (true) {
            Message<Integer> msg = consumer.receive(5, TimeUnit.SECONDS);
            if (msg == null) {
                break;
            }
            consumer.acknowledgeCumulative(msg);
            Thread.sleep(200);
            datas.remove(msg.getValue());
            log.info("received msg : {}", msg.getMessageId());
            count++;
        }
        Assert.assertEquals(count, 9);
        Assert.assertEquals(0, datas.size());
    }

    @Test(invocationCount = 5)
    public void testMultiTopicConsumerConcurrentRedeliverAndReceive() throws Exception {
        final String topic = BrokerTestUtil.newUniqueName("my-topic");
        admin.topics().createPartitionedTopic(topic, 2);

        final int receiverQueueSize = 10;

        @Cleanup
        MultiTopicsConsumerImpl<Integer> consumer =
                (MultiTopicsConsumerImpl<Integer>) pulsarClient.newConsumer(Schema.INT32)
                .topic(topic)
                .subscriptionName("sub")
                .receiverQueueSize(receiverQueueSize)
                .subscribe();
        ExecutorService internalPinnedExecutor =
                WhiteboxImpl.getInternalState(consumer, "internalPinnedExecutor");

        @Cleanup
        Producer<Integer> producer = pulsarClient.newProducer(Schema.INT32)
                .topic(topic)
                .enableBatching(false)
                .create();

        for (int i = 0; i < receiverQueueSize; i++){
            producer.send(i);
        }

        Awaitility.await().until(() -> consumer.incomingMessages.size() == receiverQueueSize);

        // For testing the race condition of issue #18491
        // We need to inject a delay for the pinned internal thread
        Thread.sleep(1000L);
        internalPinnedExecutor.submit(() -> consumer.redeliverUnacknowledgedMessages()).get();
        // Make sure the message redelivery is completed. The incoming queue will be cleaned up during the redelivery.
        internalPinnedExecutor.submit(() -> {}).get();

        Set<Integer> receivedMsgs = new HashSet<>();
        for (;;){
            Message<Integer> msg = consumer.receive(2, TimeUnit.SECONDS);
            if (msg == null){
                break;
            }
            receivedMsgs.add(msg.getValue());
        }
        Assert.assertEquals(receivedMsgs.size(), 10);

        producer.close();
        consumer.close();
        admin.topics().deletePartitionedTopic("persistent://public/default/" + topic);
    }

    @DataProvider(name = "negativeAckPrecisionBitCnt")
    public Object[][] negativeAckPrecisionBitCnt() {
        return new Object[][]{
                {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}
        };
    }

    /**
     * When negativeAckPrecisionBitCnt is greater than 0, the lower bits of the redelivery time will be truncated
     * to reduce the memory occupation. If set to k, the redelivery time will be bucketed by 2^k ms, resulting in
     * the redelivery time could be earlier(no later) than the expected time no more than 2^k ms.
     * @throws Exception if an error occurs
     */
    @Test(dataProvider = "negativeAckPrecisionBitCnt")
    public void testConfigureNegativeAckPrecisionBitCnt(int negativeAckPrecisionBitCnt) throws Exception {
        String topic = BrokerTestUtil.newUniqueName("testConfigureNegativeAckPrecisionBitCnt");
        long timeDeviation = 1L << negativeAckPrecisionBitCnt;
        long delayInMs = 2000;

        @Cleanup
        Consumer<String> consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("sub1")
                .acknowledgmentGroupTime(0, TimeUnit.SECONDS)
                .subscriptionType(SubscriptionType.Shared)
                .negativeAckRedeliveryDelay(delayInMs, TimeUnit.MILLISECONDS)
                .negativeAckRedeliveryDelayPrecision(negativeAckPrecisionBitCnt)
                .subscribe();

        @Cleanup
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topic)
                .create();
        producer.sendAsync("test-0");
        producer.flush();

        // receive the message and negative ack
        consumer.negativeAcknowledge(consumer.receive());
        long expectedTime = System.currentTimeMillis() + delayInMs;

        // receive the redelivered message and calculate the time deviation
        // assert that the redelivery time is no earlier than the `expected time - timeDeviation`
        Message<String> msg1 = consumer.receive();
        assertTrue(System.currentTimeMillis() >= expectedTime - timeDeviation);
        assertNotNull(msg1);
    }
}
