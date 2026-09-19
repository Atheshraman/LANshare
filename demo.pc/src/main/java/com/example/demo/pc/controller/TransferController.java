package com.example.demo.pc.controller;

import com.example.demo.pc.model.PendingTransfer;
import com.example.demo.pc.model.TransferrequestDTO;
import com.example.demo.pc.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@RestController
public class TransferController {
    private final TransferService transferService;
    @Value("${app.storage.download-dir}")
    private String downloadDir;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/Transfer-Request")
    public ResponseEntity<Map<String, String>> requestTransfer(@RequestBody TransferrequestDTO dto) {
        String requestID = transferService.CreateTransferRequest(dto.filename(), dto.filesize(), dto.checksum());
        return ResponseEntity.ok(Map.of("Request ID", requestID));
    }

    @GetMapping("/Transfer-Request/{requestID}")
    public ResponseEntity<PendingTransfer> getPending(@PathVariable String requestID) {
        PendingTransfer pending = transferService.getPendingTransfer(requestID);
        if (pending == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(pending);
    }

    @PostMapping("/Transfer-Request/{requestID}/accept")
    public ResponseEntity<Map<String, String>> accept(@PathVariable String requestID) {
        try {
            String token = transferService.acceptTransfer(requestID);
            return ResponseEntity.ok(Map.of("token", token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/Transfer-Request/{requestID}/reject")
    public ResponseEntity<Void> reject(@PathVariable String requestID) {
        transferService.rejectTransfer(requestID);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/upload/{requestID}")
    public ResponseEntity<Map<String, String>> uploadfile(
            @PathVariable String requestID,
            @RequestHeader("Authorization") String AuthHeader,
            HttpServletRequest request) throws Exception {
        String token = AuthHeader.replace("Bearer ", "").trim();
        if (!transferService.validateToken(requestID, token)) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid or Expired token"));
        }
        PendingTransfer pending = transferService.getPendingTransfer(requestID);
        Files.createDirectories(Paths.get(downloadDir));
        Path target = Paths.get(downloadDir, pending.getFilename());
        MessageDigest message = MessageDigest.getInstance("SHA-256");
        try (InputStream in = request.getInputStream();
             OutputStream fileout = new BufferedOutputStream(Files.newOutputStream(target), 1 << 20)
        ) {
            byte[] buffer = new byte[1 << 20];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                fileout.write(buffer, 0, bytesRead);
                message.update(buffer, 0, bytesRead);
            }

        }
        String actualchecksum = HexFormat.of().formatHex(message.digest());
        transferService.invalidateAfterUse(requestID);
        if (!actualchecksum.equalsIgnoreCase(pending.getChecksum())) {
            Files.deleteIfExists(target);
            return ResponseEntity.status(422).body(Map.of("error", "checksum mismatched, file discarded"));
        }
        return ResponseEntity.ok(Map.of("Status", "SUCCESS", "filename", pending.getFilename()));
    }

    @PostMapping("/upload/{requestId}/chunk/{chunkIndex}")
    public ResponseEntity<Map<String, Object>> uploadchunk(@PathVariable String requestId,
                                                           @PathVariable int chunkIndex,
                                                           @RequestHeader("Authorization") String authHeader,
                                                           @RequestHeader("X-Chunk-Checksum") String expectedChunkChecksum,
                                                           HttpServletRequest request) throws Exception {
        String token = authHeader.replace("Bearer ", "").trim();

        if (!transferService.validateToken(requestId, token)) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid or expired token"));
        }

        PendingTransfer pending = transferService.getPendingTransfer(requestId);
        if (pending.getChunkTransfer() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "This transfer is not chunked"));
        }

        byte[] chunkData;
        try (InputStream in = request.getInputStream()) {
            chunkData = in.readAllBytes();
        }
        if (expectedChunkChecksum != null) {
            String actualChunkChecksum = computeChunkChecksum(chunkData);
            if (!actualChunkChecksum.equalsIgnoreCase(expectedChunkChecksum)) {
                return ResponseEntity.status(422).body(Map.of(
                        "error", "Chunk checksum mismatch",
                        "chunkIndex", chunkIndex
                ));
            }
        }
        Files.createDirectories(Paths.get(downloadDir));
        Path target = Paths.get(downloadDir, pending.getFilename());
        if (!Files.exists(target)) {
            try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "rw")) {
                raf.setLength(pending.getFileSize());
            }
        }
        long offset = (long) chunkIndex * pending.getChunkSizeBytes();

        java.nio.channels.FileChannel channel = pending.getOrOpenFileChannel(target);
        channel.write(java.nio.ByteBuffer.wrap(chunkData), offset);
        pending.AddBytesReceived(chunkData.length);
        pending.getChunkTransfer().markReceived(chunkIndex);
        boolean complete = pending.getChunkTransfer().isComplete();
        Map<String, Object> body = Map.of(
                "chunkIndex", chunkIndex,
                "Received", pending.getChunkTransfer().getReceivedChunks(),
                "totalChunks", pending.getChunkTransfer().getTotalChunks(),
                "complete", complete
        );
        if (complete) {
            pending.closeFileChannel();
            String checksum = computeFileCheckSum(target);
            transferService.invalidateAfterUse(requestId);
            if (!checksum.equalsIgnoreCase(pending.getChecksum())) {
                Files.deleteIfExists(target);
                return ResponseEntity.status(422).body(Map.of("error", "Checksum mismatch, file discarded"));
            }
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/transfer-request/{requestId}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String requestId) {
        PendingTransfer pending = transferService.getPendingTransfer(requestId);
        if (pending == null) return ResponseEntity.notFound().build();

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("status", pending.getStatus().name());
        if (pending.getStatus() == com.example.demo.pc.model.TransferStatus.ACCEPTED) {
            body.put("token", pending.getToken());
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/transfer/{requestId}/progress")
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable String requestId) {
        PendingTransfer pending = transferService.getPendingTransfer(requestId);
        if (pending == null) {
            return ResponseEntity.ok(Map.of("status", "UNKNOWN", "complete", true));
        }
        long received = pending.getBytesReceived();
        long total = pending.getFileSize();
        double percent = total > 0 ? Math.min(100.0, (received * 100.0) / total) : 0;
        return ResponseEntity.ok(Map.of(
                "status", pending.getStatus().name(),
                "bytesReceived", received,
                "totalBytes", total,
                "percent", percent,
                "complete", received >= total && total > 0
        ));
    }

    @GetMapping("/transfer-requests/pending")
    public ResponseEntity<java.util.List<PendingTransfer>> getPendingRequests() {
        return ResponseEntity.ok(transferService.getAllPending());
    }

    private String computeFileCheckSum(Path target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(target)) {
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