package com.example.demo.pc.service;

import com.example.demo.pc.model.PendingTransfer;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Service;


import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TransferService {
    private final Map<String, PendingTransfer> pendingTransfer=new ConcurrentHashMap<>();
    @Value("${app.security.token-ttl-seconds}")
    private long tokenTtlSeconds;

    @Value("${app.security.pending-request-ttl-seconds}")
    private long pendingRequestTtlSeconds;
    @Value("${app.transfer.chunk-size-bytes}")
    private long chunkSizeBytes;

    @Value("${app.transfer.chunk-threshold-bytes}")
    private long chunkThresholdBytes;

    public String CreateTransferRequest(String filename,long filesize,String checksum){
        String requestID= UUID.randomUUID().toString();
        PendingTransfer pending=new PendingTransfer(requestID,filename,filesize,checksum);
        pendingTransfer.put(requestID,pending);
        return requestID;
    }
    public PendingTransfer getPendingTransfer(String requestID){
        return  pendingTransfer.get(requestID);
    }
    public String acceptTransfer(String requestID){
        PendingTransfer pending=pendingTransfer.get(requestID);
        if(pending==null){
            throw new IllegalStateException("Unknown request ID"+requestID);
        }
        String token=UUID.randomUUID().toString();
        Instant expiresAt=Instant.now().plusSeconds(tokenTtlSeconds);
        pending.accept(token,expiresAt);
        if(pending.getFileSize()>chunkThresholdBytes){
            pending.initChunking(chunkSizeBytes);
        }
        return token;
    }
    public boolean validateToken(String requestId, String token) {
        PendingTransfer pending = pendingTransfer.get(requestId);
        return pending != null && pending.isTokenValid(token);
    }

    public void invalidateAfterUse(String requestId) {
        pendingTransfer.remove(requestId);
    }
    public void rejectTransfer(String requestId) {
        PendingTransfer pending = pendingTransfer.get(requestId);
        if (pending != null) {
            pending.reject();
        }
    }
    public void purgeExpired(){
        Instant now=Instant.now();
        pendingTransfer.entrySet().removeIf(entry->{
            PendingTransfer p=entry.getValue();
            boolean expired=p.getCreatedAt().plusSeconds(pendingRequestTtlSeconds).isBefore(now);
            boolean tokenExpired=p.getTokenExpiresAt()!=null && p.getTokenExpiresAt().isBefore(now);
            return expired || tokenExpired;
        }
        );
    }
    public java.util.List<PendingTransfer> getAllPending() {
        return pendingTransfer.values().stream()
                .filter(p -> p.getStatus() == com.example.demo.pc.model.TransferStatus.PENDING)
                .toList();
    }
    public int pendingCount() {
        return pendingTransfer.size();
    }
}
