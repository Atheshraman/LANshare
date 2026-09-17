package com.example.demo.pc.controller;

import com.example.demo.pc.discovery.PeerInfo;
import com.example.demo.pc.discovery.PeerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

@RestController
public class PeerController {
    private final PeerRegistry peerRegistry;

    public PeerController(PeerRegistry peerRegistry) {
        this.peerRegistry = peerRegistry;
    }

    @GetMapping("/peers")
    public Collection<PeerInfo> getAllPeers(){
        return peerRegistry.getAllPeers();
    }
}
