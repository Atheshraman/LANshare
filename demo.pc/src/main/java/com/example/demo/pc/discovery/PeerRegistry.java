package com.example.demo.pc.discovery;


import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PeerRegistry {

    private final Map<String,PeerInfo> peers=new ConcurrentHashMap<>();

    public void addpeer(String name,PeerInfo Info){
        peers.put(name,Info);
    }
    public void removepeer(String name){
        peers.remove(name);
    }
    public Collection<PeerInfo> getAllPeers(){
        return peers.values();
    }
    public PeerInfo getPeer(String name){
        return  peers.get(name);
    }
}
