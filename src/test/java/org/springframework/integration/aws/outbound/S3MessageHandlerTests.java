/*
 * Copyright 2016-2022 the original author or authors.
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.http.client.methods.HttpRequestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.MediaType;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.expression.ValueExpression;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.FileCopyUtils;

import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressEventType;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.Headers;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.CopyObjectResult;
import com.amazonaws.services.s3.model.GetObjectMetadataRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.SetObjectAclRequest;
import com.amazonaws.services.s3.transfer.Copy;
import com.amazonaws.services.s3.transfer.PersistableTransfer;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.internal.S3ProgressListener;
import com.amazonaws.services.s3.transfer.internal.S3ProgressPublisher;
import com.amazonaws.util.BinaryUtils;
import com.amazonaws.util.Md5Utils;
import com.amazonaws.util.StringInputStream;
import com.amazonaws.util.StringUtils;

/**
 * @author Artem Bilan
 * @author John Logan
 * @author Jim Krygowski
 */
@SpringJUnitConfig
@DirtiesContext
public class S3MessageHandlerTests {

	// define the bucket and file names used throughout the test
	private static final String S3_BUCKET_NAME = "myBucket";

	private static final String S3_FILE_KEY_BAR = "subdir/bar";

	private static final String S3_FILE_KEY_FOO = "subdir/foo";

	@TempDir
	static Path temporaryFolder;

	private static SpelExpressionParser PARSER = new SpelExpressionParser();

	@Autowired
	private AmazonS3 amazonS3;

	@Autowired
	private MessageChannel s3SendChannel;

	@Autowired
	private CountDownLatch transferCompletedLatch;

	@Autowired
	private CountDownLatch aclLatch;

	@Autowired
	private MessageChannel s3ProcessChannel;

	@Autowired
	private PollableChannel s3ReplyChannel;

	@Autowired
	@Qualifier("s3MessageHandler")
	private S3MessageHandler s3MessageHandler;

	@Test
	void testUploadFile() throws IOException, InterruptedException {
		File file = new File(temporaryFolder.toFile(), "foo.mp3");
		file.createNewFile();
		Message<?> message = MessageBuilder.withPayload(file)
				.setHeader("s3Command", S3MessageHandler.Command.UPLOAD.name()).build();

		this.s3SendChannel.send(message);

		ArgumentCaptor<PutObjectRequest> putObjectRequestArgumentCaptor = ArgumentCaptor
				.forClass(PutObjectRequest.class);
		verify(this.amazonS3, atLeastOnce()).putObject(putObjectRequestArgumentCaptor.capture());

		PutObjectRequest putObjectRequest = putObjectRequestArgumentCaptor.getValue();
		assertThat(putObjectRequest.getBucketName()).isEqualTo(S3_BUCKET_NAME);
		assertThat(putObjectRequest.getKey()).isEqualTo("foo.mp3");
		assertThat(putObjectRequest.getFile()).isNotNull();
		assertThat(putObjectRequest.getInputStream()).isNull();

		ObjectMetadata metadata = putObjectRequest.getMetadata();
		assertThat(metadata.getContentMD5()).isEqualTo(Md5Utils.md5AsBase64(file));
		assertThat(metadata.getContentLength()).isEqualTo(0);
		assertThat(metadata.getContentType()).isEqualTo("audio/mpeg");

		ProgressListener listener = putObjectRequest.getGeneralProgressListener();
		S3ProgressPublisher.publishProgress(listener, ProgressEventType.TRANSFER_COMPLETED_EVENT);

		assertThat(this.transferCompletedLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(this.aclLatch.await(10, TimeUnit.SECONDS)).isTrue();

		ArgumentCaptor<SetObjectAclRequest> setObjectAclRequestArgumentCaptor = ArgumentCaptor
				.forClass(SetObjectAclRequest.class);
		verify(this.amazonS3).setObjectAcl(setObjectAclRequestArgumentCaptor.capture());

		SetObjectAclRequest setObjectAclRequest = setObjectAclRequestArgumentCaptor.getValue();

		assertThat(setObjectAclRequest.getBucketName()).isEqualTo(S3_BUCKET_NAME);
		assertThat(setObjectAclRequest.getKey()).isEqualTo("foo.mp3");
		assertThat(setObjectAclRequest.getAcl()).isNull();
		assertThat(setObjectAclRequest.getCannedAcl()).isEqualTo(CannedAccessControlList.PublicReadWrite);
	}

	@Test
	void testUploadInputStream() throws IOException {
		Expression actualKeyExpression = TestUtils.getPropertyValue(this.s3MessageHandler, "keyExpression",
				Expression.class);

		this.s3MessageHandler.setKeyExpression(null);

		InputStream payload = new StringInputStream("a");
		Message<?> message = MessageBuilder.withPayload(payload)
				.setHeader("s3Command", S3MessageHandler.Command.UPLOAD.name()).setHeader("key", "myStream").build();

		assertThatThrownBy(() -> this.s3SendChannel.send(message))
				.hasCauseExactlyInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining("Specify a 'keyExpression' for non-java.io.File payloads");

		this.s3MessageHandler.setKeyExpression(actualKeyExpression);

		this.s3SendChannel.send(message);

		ArgumentCaptor<PutObjectRequest> putObjectRequestArgumentCaptor = ArgumentCaptor
				.forClass(PutObjectRequest.class);
		verify(this.amazonS3, atLeastOnce()).putObject(putObjectRequestArgumentCaptor.capture());

		PutObjectRequest putObjectRequest = putObjectRequestArgumentCaptor.getValue();
		assertThat(putObjectRequest.getBucketName()).isEqualTo(S3_BUCKET_NAME);
		assertThat(putObjectRequest.getKey()).isEqualTo("myStream");
		assertThat(putObjectRequest.getFile()).isNull();
		assertThat(putObjectRequest.getInputStream()).isNotNull();

		ObjectMetadata metadata = putObjectRequest.getMetadata();
		assertThat(metadata.getContentMD5()).isEqualTo(Md5Utils.md5AsBase64(payload));
		assertThat(metadata.getContentLength()).isEqualTo(1);
		assertThat(metadata.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
		assertThat(metadata.getContentDisposition()).isEqualTo("test.json");
	}

	@Test
	void testUploadInputStreamNoMarkSupported() throws IOException {
		File file = new File(temporaryFolder.toFile(), "foo.mp3");
		file.createNewFile();
		FileInputStream fileInputStream = new FileInputStream(file);
		Message<?> message = MessageBuilder.withPayload(fileInputStream)
				.setHeader("s3Command", S3MessageHandler.Command.UPLOAD.name()).setHeader("key", "myStream").build();

		assertThatExceptionOfType(MessageHandlingException.class)
				.isThrownBy(() -> this.s3SendChannel.send(message))
				.withCauseInstanceOf(IllegalStateException.class);

		fileInputStream.close();
	}

	@Test
	void testUploadByteArray() {
		byte[] payload = "b".getBytes(StandardCharsets.UTF_8);
		Message<?> message = MessageBuilder.withPayload(payload)
				.setHeader("s3Command", S3MessageHandler.Command.UPLOAD.name()).setHeader("key", "myStream").build();

		this.s3SendChannel.send(message);

		ArgumentCaptor<PutObjectRequest> putObjectRequestArgumentCaptor = ArgumentCaptor
				.forClass(PutObjectRequest.class);
		verify(this.amazonS3, atLeastOnce()).putObject(putObjectRequestArgumentCaptor.capture());

		PutObjectRequest putObjectRequest = putObjectRequestArgumentCaptor.getValue();
		assertThat(putObjectRequest.getBucketName()).isEqualTo(S3_BUCKET_NAME);
		assertThat(putObjectRequest.getKey()).isEqualTo("myStream");
		assertThat(putObjectRequest.getFile()).isNull();
		assertThat(putObjectRequest.getInputStream()).isNotNull();

		ObjectMetadata metadata = putObjectRequest.getMetadata();
		assertThat(metadata.getContentMD5()).isEqualTo(Md5Utils.md5AsBase64(payload));
		assertThat(metadata.getContentLength()).isEqualTo(1);
		assertThat(metadata.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
		assertThat(metadata.getContentDisposition()).isEqualTo("test.json");
	}

	@Test
	void testDownloadDirectory() throws IOException {
		File directoryForDownload = new File(temporaryFolder.toFile(), "myFolder");
		directoryForDownload.mkdir();
		Message<?> message = MessageBuilder.withPayload(directoryForDownload)
				.setHeader("s3Command", S3MessageHandler.Command.DOWNLOAD).build();

		this.s3SendChannel.send(message);

		// get the "root" directory
		File[] directoryArray = directoryForDownload.listFiles();
		assertThat(directoryArray).isNotNull();
		assertThat(directoryArray.length).isEqualTo(1);

		File subDirectory = directoryArray[0];
		assertThat(subDirectory.getName()).isEqualTo("subdir");

		// get the files we downloaded
		File[] fileArray = subDirectory.listFiles();
		assertThat(fileArray).isNotNull();
		assertThat(fileArray.length).isEqualTo(2);

		List<File> files = Arrays.asList(fileArray);
		files.sort(Comparator.comparing(File::getName));

		File file1 = files.get(0);
		assertThat(file1.getName()).isEqualTo(S3_FILE_KEY_BAR.split("/", 2)[1]);
		assertThat(FileCopyUtils.copyToString(new FileReader(file1))).isEqualTo("bb");

		File file2 = files.get(1);
		assertThat(file2.getName()).isEqualTo(S3_FILE_KEY_FOO.split("/", 2)[1]);
		assertThat(FileCopyUtils.copyToString(new FileReader(file2))).isEqualTo("f");
	}

	@Test
	void testCopy() throws InterruptedException {
		Map<String, String> payload = new HashMap<>();
		payload.put("key", "mySource");
		payload.put("destination", "theirBucket");
		payload.put("destinationKey", "theirTarget");
		this.s3ProcessChannel.send(new GenericMessage<>(payload));

		Message<?> receive = this.s3ReplyChannel.receive(10000);
		assertThat(receive).isNotNull();

		assertThat(receive.getPayload()).isInstanceOf(Copy.class);
		Copy copy = (Copy) receive.getPayload();
		assertThat(copy.getDescription()).isEqualTo("Copying object from myBucket/mySource to theirBucket/theirTarget");

		copy.waitForCompletion();

		assertThat(copy.getState()).isEqualTo(Transfer.TransferState.Completed);
	}

	@Configuration
	@EnableIntegration
	public static class ContextConfiguration {

		@Bean
		public AmazonS3 amazonS3() {
			AmazonS3 amazonS3 = mock(AmazonS3.class);

			given(amazonS3.putObject(any(PutObjectRequest.class))).willReturn(new PutObjectResult());
			ObjectMetadata objectMetadata = new ObjectMetadata();
			objectMetadata.setLastModified(new Date());
			given(amazonS3.getObjectMetadata(any(GetObjectMetadataRequest.class))).willReturn(objectMetadata);
			given(amazonS3.copyObject(any(CopyObjectRequest.class))).willReturn(new CopyObjectResult());

			ObjectListing objectListing = spy(new ObjectListing());

			List<S3ObjectSummary> s3ObjectSummaries = new LinkedList<>();

			S3ObjectSummary fileSummary1 = new S3ObjectSummary();
			fileSummary1.setBucketName(S3_BUCKET_NAME);
			fileSummary1.setKey(S3_FILE_KEY_FOO);
			fileSummary1.setSize(1);
			s3ObjectSummaries.add(fileSummary1);

			S3ObjectSummary fileSummary2 = new S3ObjectSummary();
			fileSummary2.setBucketName(S3_BUCKET_NAME);
			fileSummary2.setKey(S3_FILE_KEY_BAR);
			fileSummary2.setSize(2);
			s3ObjectSummaries.add(fileSummary2);

			given(objectListing.getObjectSummaries()).willReturn(s3ObjectSummaries);
			given(amazonS3.listObjects(any(ListObjectsRequest.class))).willReturn(objectListing);

			final S3Object file1 = new S3Object();
			file1.setBucketName(S3_BUCKET_NAME);
			file1.setKey(S3_FILE_KEY_FOO);
			try {
				byte[] data = "f".getBytes(StringUtils.UTF8);
				byte[] md5 = Md5Utils.computeMD5Hash(data);
				file1.getObjectMetadata().setHeader(Headers.ETAG, BinaryUtils.toHex(md5));
				S3ObjectInputStream content = new S3ObjectInputStream(new ByteArrayInputStream(data),
						mock(HttpRequestBase.class));
				file1.setObjectContent(content);
			}
			catch (Exception e) {
				// no-op
			}

			final S3Object file2 = new S3Object();
			file2.setBucketName(S3_BUCKET_NAME);
			file2.setKey(S3_FILE_KEY_BAR);
			try {
				byte[] data = "bb".getBytes(StringUtils.UTF8);
				byte[] md5 = Md5Utils.computeMD5Hash(data);
				file2.getObjectMetadata().setHeader(Headers.ETAG, BinaryUtils.toHex(md5));
				S3ObjectInputStream content = new S3ObjectInputStream(new ByteArrayInputStream(data),
						mock(HttpRequestBase.class));
				file2.setObjectContent(content);
			}
			catch (Exception e) {
				// no-op
			}

			willAnswer(invocation -> {
				GetObjectRequest getObjectRequest = (GetObjectRequest) invocation.getArguments()[0];
				String key = getObjectRequest.getKey();
				if (S3_FILE_KEY_FOO.equals(key)) {
					return file1;
				}
				else if (S3_FILE_KEY_BAR.equals(key)) {
					return file2;
				}
				else {
					return invocation.callRealMethod();
				}
			}).given(amazonS3).getObject(any(GetObjectRequest.class));

			willAnswer(invocation -> {
				aclLatch().countDown();
				return null;
			}).given(amazonS3).setObjectAcl(any(SetObjectAclRequest.class));

			return amazonS3;
		}

		@Bean
		public CountDownLatch aclLatch() {
			return new CountDownLatch(1);
		}

		@Bean
		public CountDownLatch transferCompletedLatch() {
			return new CountDownLatch(1);
		}

		@Bean
		public S3ProgressListener s3ProgressListener() {
			return new S3ProgressListener() {

				@Override
				public void onPersistableTransfer(PersistableTransfer persistableTransfer) {

				}

				@Override
				public void progressChanged(ProgressEvent progressEvent) {
					if (ProgressEventType.TRANSFER_COMPLETED_EVENT.equals(progressEvent.getEventType())) {
						transferCompletedLatch().countDown();
					}
				}

			};
		}

		@Bean
		@ServiceActivator(inputChannel = "s3SendChannel")
		public MessageHandler s3MessageHandler() {
			S3MessageHandler s3MessageHandler = new S3MessageHandler(amazonS3(), S3_BUCKET_NAME);
			s3MessageHandler.setCommandExpression(PARSER.parseExpression("headers.s3Command"));
			Expression keyExpression = PARSER
					.parseExpression("payload instanceof T(java.io.File) ? payload.name : headers.key");
			s3MessageHandler.setKeyExpression(keyExpression);
			s3MessageHandler.setObjectAclExpression(new ValueExpression<>(CannedAccessControlList.PublicReadWrite));
			s3MessageHandler.setUploadMetadataProvider((metadata, message) -> {
				if (message.getPayload() instanceof InputStream || message.getPayload() instanceof byte[]) {
					metadata.setContentLength(1);
					metadata.setContentType(MediaType.APPLICATION_JSON_VALUE);
					metadata.setContentDisposition("test.json");
				}
			});
			s3MessageHandler.setProgressListener(s3ProgressListener());
			return s3MessageHandler;
		}

		@Bean
		public PollableChannel s3ReplyChannel() {
			return new QueueChannel();
		}

		@Bean
		@ServiceActivator(inputChannel = "s3ProcessChannel")
		public MessageHandler s3ProcessMessageHandler() {
			S3MessageHandler s3MessageHandler = new S3MessageHandler(amazonS3(), S3_BUCKET_NAME, true);
			s3MessageHandler.setOutputChannel(s3ReplyChannel());
			s3MessageHandler.setCommand(S3MessageHandler.Command.COPY);
			s3MessageHandler.setKeyExpression(PARSER.parseExpression("payload.key"));
			s3MessageHandler.setDestinationBucketExpression(PARSER.parseExpression("payload.destination"));
			s3MessageHandler.setDestinationKeyExpression(PARSER.parseExpression("payload.destinationKey"));
			return s3MessageHandler;
		}

	}

}
