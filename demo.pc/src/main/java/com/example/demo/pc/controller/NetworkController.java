package com.example.demo.pc.controller;
import com.example.demo.pc.service.NetworkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class NetworkController {

    @Value("${server.port}")
    private int serverPort;

    @GetMapping("/api/network/self")
    public Map<String, Object> getSelf() {
        String ip = NetworkService.getLocalIPv4();
        return Map.of(
                "ip", ip,
                "port", serverPort,
                "baseUrl", "http://" + ip + ":" + serverPort
        );
    }
}