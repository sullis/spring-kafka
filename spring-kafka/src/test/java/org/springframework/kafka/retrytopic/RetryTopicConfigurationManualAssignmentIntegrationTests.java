/*
 * Copyright 2021-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.retrytopic;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * @author Gary Russell
 * @since 2.7.7
 *
 */
@SpringJUnitConfig
@DirtiesContext
@EmbeddedKafka(topics = { RetryTopicConfigurationManualAssignmentIntegrationTests.TOPIC1,
		RetryTopicConfigurationManualAssignmentIntegrationTests.TOPIC2 }, partitions = 1)
class RetryTopicConfigurationManualAssignmentIntegrationTests {

	public static final String TOPIC1 = "RetryTopicConfigurationManualAssignmentIntegrationTests.1";

	public static final String TOPIC2 = "RetryTopicConfigurationManualAssignmentIntegrationTests.2";

	@Test
	void includeTopic(@Autowired EmbeddedKafkaBroker broker, @Autowired ConsumerFactory<Integer, String> cf,
			@Autowired KafkaTemplate<Integer, String> template, @Autowired Config config) throws InterruptedException {

		Consumer<Integer, String> consumer = cf.createConsumer("grp2", "");
		try {
			await().untilAsserted(() -> {
				Map<String, List<PartitionInfo>> topics = consumer.listTopics();
				assertThat(topics.keySet()).contains("RetryTopicConfigurationManualAssignmentIntegrationTests.1",
						"RetryTopicConfigurationManualAssignmentIntegrationTests.1-dlt",
						"RetryTopicConfigurationManualAssignmentIntegrationTests.1-retry-100",
						"RetryTopicConfigurationManualAssignmentIntegrationTests.1-retry-110",
						"RetryTopicConfigurationManualAssignmentIntegrationTests.2",
						"RetryTopicConfigurationManualAssignmentIntegrationTests.2-dlt",
						"RetryTopicConfigurationManualAssignmentIntegrationTests.2-retry-100",
						"RetryTopicConfigurationManualAssignmentIntegrationTests.2-retry-110");
			});
			template.send(TOPIC1, "foo");
			assertThat(config.latch.await(120, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			consumer.close(Duration.ofSeconds(10));
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableKafka
	static class Config extends RetryTopicConfigurationSupport {

		private final CountDownLatch latch = new CountDownLatch(1);

		@KafkaListener(id = TOPIC1, topicPartitions = {
				@TopicPartition(topic = TOPIC1, partitions = "0"),
				@TopicPartition(topic = TOPIC1, partitions = "1"),
				@TopicPartition(topic = TOPIC2, partitions = "0") })
		void listen1(String in) {
			throw new RuntimeException("test");
		}

		void dlt(String in) {
			this.latch.countDown();
		}

		@Bean
		KafkaListenerContainerFactory<?> kafkaListenerContainerFactory(KafkaTemplate<Integer, String> template,
				ConsumerFactory<Integer, String> consumerFactory) {

			ConcurrentKafkaListenerContainerFactory<Integer, String> factory =
					new ConcurrentKafkaListenerContainerFactory<>();
			factory.setConsumerFactory(consumerFactory);
			factory.setReplyTemplate(template);
			return factory;
		}

		@Bean
		ConsumerFactory<Integer, String> consumerFactory(EmbeddedKafkaBroker embeddedKafka) {
			Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "retryConfig", false);
			props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000);
			return new DefaultKafkaConsumerFactory<>(
					props);
		}

		@Bean
		KafkaTemplate<Integer, String> template(ProducerFactory<Integer, String> producerFactory) {
			return new KafkaTemplate<>(producerFactory);
		}

		@Bean
		ProducerFactory<Integer, String> producerFactory(EmbeddedKafkaBroker embeddedKafka) {
			return new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(embeddedKafka));
		}

		@Bean
		KafkaAdmin admin(EmbeddedKafkaBroker broker) {
			return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
		}

		@Bean
		RetryTopicConfiguration retryTopicConfiguration1(KafkaTemplate<Integer, String> template) {
			return RetryTopicConfigurationBuilder.newInstance()
					.includeTopics(List.of(TOPIC1, TOPIC2))
					.exponentialBackoff(100, 1.1, 110)
					.dltHandlerMethod("retryTopicConfigurationManualAssignmentIntegrationTests.Config", "dlt")
					.create(template);
		}

		@Bean
		TaskScheduler sched() {
			return new ThreadPoolTaskScheduler();
		}

	}

}
