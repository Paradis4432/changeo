package ar.changeo.files;

import ar.changeo.moderation.ContentDigest;
import java.io.*;
import java.net.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode;
import software.amazon.awssdk.core.checksums.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.Abortable;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.http.apache5.ProxyConfiguration;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

final class R2PrivateBlobStore implements PrivateBlobStore, AutoCloseable {
    private final S3Client client;
    private final String bucket;
    private final String storeId;
    private final PrivateBlobJournal journal;

    R2PrivateBlobStore(URI endpoint, String bucket, String accessKey, String secretKey, PrivateBlobJournal journal) {
        this.bucket = bucket;
        this.storeId = ContentDigest.bytes((endpoint.toASCIIString() + "/" + bucket + "/private-blobs/").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.journal = journal;
        try {
            this.client = S3Client.builder().endpointOverride(endpoint).region(Region.of("auto"))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                    .defaultsMode(DefaultsMode.STANDARD)
                    .httpClientBuilder(Apache5HttpClient.builder().maxConnections(20)
                            .connectionAcquisitionTimeout(Duration.ofSeconds(1)).connectionTimeout(Duration.ofSeconds(1))
                            .socketTimeout(Duration.ofSeconds(2)).expectContinueEnabled(false).useIdleConnectionReaper(false)
                            .proxyConfiguration(ProxyConfiguration.builder().useSystemPropertyValues(false).useEnvironmentVariableValues(false).build()))
                    .crossRegionAccessEnabled(false).disableMultiRegionAccessPoints(true).disableS3ExpressSessionAuth(true)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .defaultProfileFile(ProfileFile.builder().type(ProfileFile.Type.CONFIGURATION).content(new ByteArrayInputStream(new byte[0])).build())
                            .apiCallAttemptTimeout(Duration.ofSeconds(5)).apiCallTimeout(Duration.ofSeconds(6))
                            .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                            .addExecutionInterceptor(new ResponseBounds()).build()).build();
        } catch (RuntimeException failure) { throw PrivateBlobStore.unavailable(); }
    }

    String storeId() { return storeId; }

    @Override public Remote create(UUID fileId, String mediaType, byte[] bytes) {
        Objects.requireNonNull(fileId);
        PrivateBlobStore.requireSize(bytes.length);
        if (mediaType == null || !mediaType.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") || mediaType.length() > 80) { throw PrivateBlobStore.unavailable(); }
        byte[] immutable = bytes.clone();
        journal.attempt(fileId, storeId, mediaType, immutable);
        Remote blob;
        try {
            var response = client.putObject(PutObjectRequest.builder().bucket(bucket).key("private-blobs/" + fileId)
                    .ifNoneMatch("*").contentLength((long) immutable.length).contentType(mediaType).contentMD5(md5(immutable)).build(),
                    RequestBody.fromBytes(immutable));
            blob = new Remote(fileId, storeId, "private-blobs/" + fileId, response.eTag());
        } catch (RuntimeException failure) {
            journal.outcome(fileId, PrivateBlobJournal.Outcome.QUARANTINED);
            throw PrivateBlobStore.unavailable();
        }
        journal.outcome(fileId, PrivateBlobJournal.Outcome.CREATED);
        return blob;
    }

    @Override public byte[] read(Blob blob, String mediaType, long expectedSize) {
        if (!(blob instanceof Remote remote) || !storeId.equals(remote.storeId())) { throw PrivateBlobStore.unavailable(); }
        PrivateBlobStore.requireSize(expectedSize);
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(remote.objectKey()).ifMatch(remote.etag()).build(), (response, stream) -> {
                try {
                    if (!Long.valueOf(expectedSize).equals(response.contentLength()) || !mediaType.equals(response.contentType())
                            || !remote.etag().equals(response.eTag())
                            || (response.contentEncoding() != null && !response.contentEncoding().equals("identity"))) { throw PrivateBlobStore.unavailable(); }
                    byte[] bytes = new byte[(int) expectedSize];
                    int offset = 0;
                    while (offset < bytes.length) {
                        if (Thread.currentThread().isInterrupted()) { throw new InterruptedException(); }
                        int count = stream.read(bytes, offset, bytes.length - offset);
                        if (count < 0) { throw PrivateBlobStore.unavailable(); }
                        offset += count;
                    }
                    if (stream.read() != -1 || Thread.currentThread().isInterrupted()) { throw PrivateBlobStore.unavailable(); }
                    return bytes;
                } catch (IOException | RuntimeException | InterruptedException failure) { stream.abort(); throw failure; }
            });
        } catch (RuntimeException failure) { throw PrivateBlobStore.unavailable(); }
    }

    @Override public Cleanup rollback(Blob created) {
        if (!(created instanceof Remote remote) || !storeId.equals(remote.storeId())) { throw PrivateBlobStore.unavailable(); }
        journal.completion(remote.fileId(), PrivateBlobJournal.Completion.RETAINED);
        return Cleanup.RETAINED;
    }

    void committed(Remote blob) { journal.completion(blob.fileId(), PrivateBlobJournal.Completion.COMMITTED); }

    @Override public void close() { client.close(); }

    private static String md5(byte[] bytes) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static final class ResponseBounds implements ExecutionInterceptor {
        private static final ExecutionAttribute<Long> DEADLINE = new ExecutionAttribute<>("private-blob-deadline");

        @Override public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
            attributes.putAttribute(DEADLINE, System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
        }

        @Override public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context, ExecutionAttributes attributes) {
            long limit = context.httpResponse().isSuccessful() && context.request() instanceof GetObjectRequest ? MAX_BYTES + 1L : 16_384L;
            return context.responseBody().map(input -> {
                if (!(input instanceof Abortable abortable)) { throw PrivateBlobStore.unavailable(); }
                return new BoundedResponse(input, abortable, limit, attributes.getAttribute(DEADLINE));
            });
        }
    }

    private static final class BoundedResponse extends InputStream implements Abortable {
        private final InputStream input;
        private final Abortable original;
        private final long deadline;
        private long remaining;
        private boolean eof;

        BoundedResponse(InputStream input, Abortable original, long limit, long deadline) {
            this.input = input; this.original = original; this.remaining = limit; this.deadline = deadline;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) { return 0; }
            if (eof) { return -1; }
            try {
                requireAvailable();
                int count = input.read(bytes, offset, (int) Math.min(length, remaining));
                if (count > 0) { remaining -= count; }
                if (count < 0) { eof = true; }
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) { throw new InterruptedIOException("Private storage response interrupted"); }
                return count;
            } catch (IOException | RuntimeException failure) { abort(); throw failure; }
        }

        @Override public void abort() { original.abort(); }

        @Override public void close() throws IOException {
            if (!eof) { abort(); }
            input.close();
        }

        private void requireAvailable() throws IOException {
            if (remaining <= 0) { throw new IOException("Private storage response exceeds limit"); }
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) { throw new InterruptedIOException("Private storage response interrupted"); }
        }
    }
}
