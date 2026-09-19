package com.example.demo.pc.controller;

import com.example.demo.pc.client.TransferClientService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@Slf4j
public class SendController {
    private final TransferClientService transferClientService;

    public SendController(TransferClientService transferClientService) {
        this.transferClientService = transferClientService;
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> send(
            @RequestParam String peerUrl,
            @RequestParam String filename,
            HttpServletRequest request) throws Exception {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path tempDir = (localAppData != null)
                ? Path.of(localAppData, "LANShare", "temp-outgoing")
                : Path.of(System.getProperty("java.io.tmpdir"), "LANShare-temp-outgoing");
        Files.createDirectories(tempDir);
        Path tempFile = tempDir.resolve(filename);
        try (InputStream in = request.getInputStream();
             OutputStream out = Files.newOutputStream(tempFile);
        ) {
            in.transferTo(out);
        }
        String requestId;
        try {
            requestId = transferClientService.initiateTransfer(peerUrl, tempFile);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception cleanupEx) {
                log.warn("Failed to delete temporary file: {}", tempFile, cleanupEx);
            }
            return ResponseEntity.status(502).body(Map.of(
                    "error", "Could not reach peer or register transfer",
                    "detail", String.valueOf(e.getMessage())
            ));
        }

        final String finalRequestId = requestId;
        Thread.ofVirtual().start(() -> {
            try {
                transferClientService.completeTransfer(peerUrl, finalRequestId, tempFile);
                System.out.println("[Send] Completed: " + filename + " -> " + peerUrl);
            } catch (Exception e) {
                System.err.println("[Send] Failed: " + filename + " -> " + peerUrl + " : " + e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.warn("Failed to delete temporary file: {}", tempFile, e);
                }
            }
        });
        return ResponseEntity.ok(Map.of("status", "send-initiated", "filename", filename));
    }
}
