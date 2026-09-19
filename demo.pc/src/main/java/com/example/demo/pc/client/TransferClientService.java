package com.example.demo.pc.client;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TransferClientService {

    private final HttpClientProvider httpClientProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.transfer.chunk-size-bytes}")
    private long chunkSizeBytes;

    @Value("${app.transfer.chunk-threshold-bytes}")
    private long chunkThresholdBytes;

    @Value("${app.transfer.parallel-chunk-uploads}")
    private int parallelChunkUploads;
    private static final int MAX_RETRIES = 3;

    public TransferClientService(HttpClientProvider httpClientProvider) {
        this.httpClientProvider = httpClientProvider;
    }

    public void sendFile(String peerBaseUrl, Path filePath) throws Exception {
        String requestID = initiateTransfer(peerBaseUrl, filePath);
        completeTransfer(peerBaseUrl, requestID, filePath);
    }

    public String initiateTransfer(String peerBaseUrl, Path filePath) throws Exception {
        long fileSize = Files.size(filePath);
        String checksum = computeChecksum(filePath);
        String filename = filePath.getFileName().toString();
        String requestID = sendTransferRequest(peerBaseUrl, filename, fileSize, checksum);
        System.out.println("[Client] transfer request created " + requestID);
        return requestID;
    }

    public void completeTransfer(String peerBaseUrl, String requestID, Path filePath) throws Exception {
        long fileSize = Files.size(filePath);
        String filename = filePath.getFileName().toString();

        String token = waitForAcceptance(peerBaseUrl, requestID);
        System.out.println("[client] transfer Accepted, token received");

        if (fileSize > chunkThresholdBytes) {
            uploadInChunks(peerBaseUrl, requestID, token, filePath, fileSize);
        } else {
            uploadInOneStream(peerBaseUrl, requestID, token, filePath);
        }
        System.out.println("[Client] transfer complete " + filename);
    }

    private String sendTransferRequest(String peerBaseUrl, String filename, long fileSize, String checksum)
            throws Exception {
        Map<String, Object> body = Map.of(
                "filename", filename,
                "filesize", fileSize,
                "checksum", checksum
        );
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(peerBaseUrl + "/Transfer-Request"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> Response = httpClientProvider.getHttpClient().
                send(request, HttpResponse.BodyHandlers.ofString());
        if (Response.statusCode() != 200) {
            throw new IllegalStateException("Transfer Request Failed" + Response.body());
        }
        Map<?, ?> responseBody = objectMapper.readValue(Response.body(), Map.class);
        return (String) responseBody.get("Request ID");
    }

    private String waitForAcceptance(String peerBaseUrl, String requestID) throws IOException, InterruptedException {
        int max_Attempts = 60;
        for (int i = 0; i < max_Attempts; i++) {
            HttpRequest statusRequest = HttpRequest.newBuilder()
                    .uri(URI.create(peerBaseUrl + "/transfer-request/" + requestID + "/status"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClientProvider.getHttpClient()
                    .send(statusRequest, HttpResponse.BodyHandlers.ofString());

            Map<?, ?> body = objectMapper.readValue(response.body(), Map.class);
            String status = (String) body.get("status");

            if ("ACCEPTED".equals(status)) {
                return (String) body.get("token");
            }
            if ("REJECTED".equals(status)) {
                throw new IllegalStateException("Receiver rejected the transfer");
            }

            Thread.sleep(2000);
        }
        throw new IllegalStateException("receiver failed to respond in time");
    }

    private void uploadInOneStream(String peerBaseUrl, String requestID, String token, Path filePath) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(peerBaseUrl + "/upload/" + requestID))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofFile(filePath))
                .build();
        HttpResponse<String> response = httpClientProvider.getHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("upload failed" + response.body());
        }
    }

    private void uploadInChunks(String peerBaseUrl, String requestID, String token, Path filePath, long fileSize) throws InterruptedException {

        int totalChunks = (int) Math.ceil((double) fileSize / chunkSizeBytes);
        ExecutorService pool = Executors.newFixedThreadPool(parallelChunkUploads);
        CountDownLatch latch = new CountDownLatch(totalChunks);
        ConcurrentLinkedQueue<Exception> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < totalChunks; i++) {
            final int chunkIndex = i;
            pool.submit(() -> {
                try {
                    uploadChunksWithRetry(peerBaseUrl, requestID, token, filePath, chunkIndex, fileSize);
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("one or more chunks failed" + errors.peek().getMessage());
        }
    }

    private void uploadChunksWithRetry(String peerBaseUrl, String requestID, String token, Path filePath, int chunkIndex, long fileSize) throws InterruptedException {

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                uploadSingleChunk(peerBaseUrl, requestID, token, filePath, chunkIndex, fileSize);
                return;
            } catch (Exception e) {
                lastError = e;
                System.out.println("\"[Client] Chunk \" + chunkIndex + \" failed (attempt \" + attempt + \"): \" " + e.getMessage());
                Thread.sleep(500L * attempt);

            }
        }
        throw new IllegalStateException("Chunk " + chunkIndex + " failed after " + MAX_RETRIES + " attempts", lastError);

    }

    private void uploadSingleChunk(String peerBaseUrl, String requestID, String token, Path filePath, int chunkIndex, long fileSize) throws Exception {
        long offset = (long) chunkIndex * chunkSizeBytes;
        long length = Math.min(chunkSizeBytes, fileSize - offset);

        byte[] chunkData = readChunk(filePath, offset, length);
        String ChunkChecksum = computeChunkChecksum(chunkData);


        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(peerBaseUrl + "/upload/" + requestID + "/chunk/" + chunkIndex))
                .header("Authorization", "Bearer " + token)
                .header("X-Chunk-Checksum", ChunkChecksum)
                .POST(HttpRequest.BodyPublishers.ofByteArray(chunkData))
                .build();

        HttpResponse<String> response = httpClientProvider.getHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Chunk upload failed: " + response.body());
        }
    }

    private byte[] readChunk(Path filePath, long offset, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            raf.seek(offset);
            byte[] buffer = new byte[(int) length];
            raf.readFully(buffer);
            return buffer;
        }
    }

    private String computeChecksum(Path filePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[1 << 20];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String computeChunkChecksum(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(data);
        return HexFormat.of().formatHex(digest.digest());
    }

}
