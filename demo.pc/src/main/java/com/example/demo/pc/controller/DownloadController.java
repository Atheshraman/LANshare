package com.example.demo.pc.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
public class DownloadController {
    @Value("${app.storage.download-dir}")
    private String downloadDir;

    @GetMapping("/api/storage/download-dir")
    public Map<String, String> getDownloadDir() {
        Path resolved = Paths.get(downloadDir).toAbsolutePath().normalize();
        return Map.of("path", resolved.toString());
    }
}
