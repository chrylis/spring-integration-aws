/*
 * Copyright 2017-2022 the original author or authors.
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

package org.springframework.integration.aws.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.aws.support.AwsRequestFailureException;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.kinesis.AmazonKinesisAsync;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.PutRecordResult;
import com.amazonaws.services.kinesis.model.PutRecordsRequest;
import com.amazonaws.services.kinesis.model.PutRecordsRequestEntry;
import com.amazonaws.services.kinesis.model.PutRecordsResult;

/**
 * @author Jacob Severson
 * @author Artem Bilan
 *
 * @since 1.1
 */
@SpringJUnitConfig
@DirtiesContext
public class KinesisProducingMessageHandlerTests {

	@Autowired
	protected MessageChannel kinesisSendChannel;

	@Autowired
	protected KinesisMessageHandler kinesisMessageHandler;

	@Autowired
	protected PollableChannel errorChannel;

	@Autowired
	protected PollableChannel successChannel;

	@Test
	@SuppressWarnings("unchecked")
	public void testKinesisMessageHandler() {
		final Message<?> message = MessageBuilder.withPayload("message").build();

		assertThatExceptionOfType(MessageHandlingException.class)
				.isThrownBy(() -> this.kinesisSendChannel.send(message))
				.withCauseInstanceOf(IllegalStateException.class)
				.withStackTraceContaining("'stream' must not be null for sending a Kinesis record");

		this.kinesisMessageHandler.setStream("foo");

		assertThatExceptionOfType(MessageHandlingException.class)
				.isThrownBy(() -> this.kinesisSendChannel.send(message))
				.withCauseInstanceOf(IllegalStateException.class)
				.withStackTraceContaining("'partitionKey' must not be null for sending a Kinesis record");

		Message<?> message2 = MessageBuilder.fromMessage(message).setHeader(AwsHeaders.PARTITION_KEY, "fooKey")
				.setHeader(AwsHeaders.SEQUENCE_NUMBER, "10").build();

		this.kinesisSendChannel.send(message2);

		Message<?> success = this.successChannel.receive(10000);
		assertThat(success.getHeaders().get(AwsHeaders.PARTITION_KEY)).isEqualTo("fooKey");
		assertThat(success.getHeaders().get(AwsHeaders.SEQUENCE_NUMBER)).isEqualTo("10");
		assertThat(success.getPayload()).isEqualTo("message");

		message2 = MessageBuilder.fromMessage(message).setHeader(AwsHeaders.PARTITION_KEY, "fooKey")
				.setHeader(AwsHeaders.SEQUENCE_NUMBER, "10").build();

		this.kinesisSendChannel.send(message2);

		Message<?> failed = this.errorChannel.receive(10000);
		AwsRequestFailureException putRecordFailure = (AwsRequestFailureException) failed.getPayload();
		assertThat(putRecordFailure.getCause().getMessage()).isEqualTo("putRecordRequestEx");
		assertThat(((PutRecordRequest) putRecordFailure.getRequest()).getStreamName()).isEqualTo("foo");
		assertThat(((PutRecordRequest) putRecordFailure.getRequest()).getPartitionKey()).isEqualTo("fooKey");
		assertThat(((PutRecordRequest) putRecordFailure.getRequest()).getSequenceNumberForOrdering()).isEqualTo("10");
		assertThat(((PutRecordRequest) putRecordFailure.getRequest()).getExplicitHashKey()).isNull();
		assertThat(((PutRecordRequest) putRecordFailure.getRequest()).getData())
				.isEqualTo(ByteBuffer.wrap("message".getBytes()));

		message2 = new GenericMessage<>(new PutRecordsRequest().withStreamName("myStream").withRecords(
				new PutRecordsRequestEntry().withData(ByteBuffer.wrap("test".getBytes())).withPartitionKey("testKey")));

		this.kinesisSendChannel.send(message2);

		success = this.successChannel.receive(10000);
		assertThat(((PutRecordsRequest) success.getPayload()).getRecords()).containsExactlyInAnyOrder(
				new PutRecordsRequestEntry().withData(ByteBuffer.wrap("test".getBytes())).withPartitionKey("testKey"));

		message2 = new GenericMessage<>(new PutRecordsRequest().withStreamName("myStream").withRecords(
				new PutRecordsRequestEntry().withData(ByteBuffer.wrap("test".getBytes())).withPartitionKey("testKey")));

		this.kinesisSendChannel.send(message2);

		failed = this.errorChannel.receive(10000);
		AwsRequestFailureException putRecordsFailure = (AwsRequestFailureException) failed.getPayload();
		assertThat(putRecordsFailure.getCause().getMessage()).isEqualTo("putRecordsRequestEx");
		assertThat(((PutRecordsRequest) putRecordsFailure.getRequest()).getStreamName()).isEqualTo("myStream");
		assertThat(((PutRecordsRequest) putRecordsFailure.getRequest()).getRecords()).containsExactlyInAnyOrder(
				new PutRecordsRequestEntry().withData(ByteBuffer.wrap("test".getBytes())).withPartitionKey("testKey"));
	}

	@Configuration
	@EnableIntegration
	public static class ContextConfiguration {

		@Bean
		@SuppressWarnings("unchecked")
		public AmazonKinesisAsync amazonKinesis() {
			AmazonKinesisAsync mock = mock(AmazonKinesisAsync.class);

			given(mock.putRecordAsync(any(PutRecordRequest.class), any(AsyncHandler.class))).willAnswer(invocation -> {
				PutRecordRequest request = invocation.getArgument(0);
				AsyncHandler<PutRecordRequest, PutRecordResult> handler = invocation.getArgument(1);
				PutRecordResult result = new PutRecordResult()
						.withSequenceNumber(request.getSequenceNumberForOrdering()).withShardId("shardId-1");
				handler.onSuccess(new PutRecordRequest(), result);
				return mock(Future.class);
			}).willAnswer(invocation -> {
				AsyncHandler<?, ?> handler = invocation.getArgument(1);
				handler.onError(new RuntimeException("putRecordRequestEx"));
				return mock(Future.class);
			});

			given(mock.putRecordsAsync(any(PutRecordsRequest.class), any(AsyncHandler.class)))
					.willAnswer(invocation -> {
						AsyncHandler<PutRecordsRequest, PutRecordsResult> handler = invocation.getArgument(1);
						handler.onSuccess(new PutRecordsRequest(), new PutRecordsResult());
						return mock(Future.class);
					}).willAnswer(invocation -> {
				AsyncHandler<?, ?> handler = invocation.getArgument(1);
				handler.onError(new RuntimeException("putRecordsRequestEx"));
				return mock(Future.class);
			});

			return mock;
		}

		@Bean
		public PollableChannel errorChannel() {
			return new QueueChannel();
		}

		@Bean
		public PollableChannel successChannel() {
			return new QueueChannel();
		}

		@Bean
		@ServiceActivator(inputChannel = "kinesisSendChannel")
		public MessageHandler kinesisMessageHandler() {
			KinesisMessageHandler kinesisMessageHandler = new KinesisMessageHandler(amazonKinesis());
			kinesisMessageHandler.setSync(true);
			kinesisMessageHandler.setOutputChannel(successChannel());
			kinesisMessageHandler.setFailureChannel(errorChannel());
			kinesisMessageHandler.setMessageConverter(new MessageConverter() {

				private SerializingConverter serializingConverter = new SerializingConverter();

				@Override
				public Object fromMessage(Message<?> message, Class<?> targetClass) {
					Object source = message.getPayload();
					if (source instanceof String) {
						return ((String) source).getBytes();
					}
					else {
						return this.serializingConverter.convert(source);
					}
				}

				@Override
				public Message<?> toMessage(Object payload, MessageHeaders headers) {
					return null;
				}

			});
			return kinesisMessageHandler;
		}

	}

}
