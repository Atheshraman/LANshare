package com.example.demo.pc.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;


public class PendingTransfer {
    private final String requestId;
    private final String filename;
    private final long fileSize;
    private final String checksum;
    private final Instant createdAt;
    private String token;
    private Instant tokenExpiresAt;
    private TransferStatus status = TransferStatus.PENDING;
    private ChunkTransfer chunkTransfer;
    private long chunkSizeBytes;
    private java.nio.channels.FileChannel fileChannel;

    private final AtomicLong BytesReceived = new AtomicLong(0);

    public PendingTransfer(String requestId, String filename, long fileSize, String checksum) {
        this.requestId = requestId;
        this.filename = filename;
        this.fileSize = fileSize;
        this.checksum = checksum;
        this.createdAt = Instant.now();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getFilename() {
        return filename;
    }

    public long getFileSize() {
        return fileSize;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getToken() {
        return token;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void accept(String token, Instant expiresAt) {
        this.status = TransferStatus.ACCEPTED;
        this.token = token;
        this.tokenExpiresAt = expiresAt;
    }

    public void reject() {
        this.status = TransferStatus.REJECTED;
    }

    public boolean isTokenValid(String suppliedToken) {
        return status == TransferStatus.ACCEPTED
                && token != null
                && token.equals(suppliedToken)
                && Instant.now().isBefore(tokenExpiresAt);
    }

    public void initChunking(long chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
        int totalChunks = (int) Math.ceil((double) this.fileSize / chunkSizeBytes);
        this.chunkTransfer = new ChunkTransfer(totalChunks);

    }

    public ChunkTransfer getChunkTransfer() {
        return chunkTransfer;
    }

    public long getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public long getBytesReceived() {
        return BytesReceived.get();
    }

    public void AddBytesReceived(long n) {
        BytesReceived.addAndGet(n);
    }
    public synchronized java.nio.channels.FileChannel getOrOpenFileChannel(java.nio.file.Path target) throws java.io.IOException {
        if (fileChannel == null) {
            fileChannel = java.nio.channels.FileChannel.open(
                    target,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE
            );
        }
        return fileChannel;
    }

    public synchronized void closeFileChannel() {
        if (fileChannel != null) {
            try {
                fileChannel.close();
            } catch (java.io.IOException e) {
                System.err.println("Failed to close file channel for " + requestId + ": " + e.getMessage());
            }
            fileChannel = null;
        }
    }
}

