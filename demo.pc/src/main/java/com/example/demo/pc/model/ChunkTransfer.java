package com.example.demo.pc.model;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkTransfer {
    private final int totalChunks;
    private final Set<Integer> receivedchunks=ConcurrentHashMap.newKeySet();


    public ChunkTransfer(int totalChunks) {
        this.totalChunks = totalChunks;
    }
    public void markReceived(int ChunkIndex){
        receivedchunks.add(ChunkIndex);
    }
    public boolean isChunkReceived(int ChunkIndex){
        return receivedchunks.contains(ChunkIndex);
    }
    public boolean isComplete(){
        return receivedchunks.size()==totalChunks;

    }
    public int getReceivedChunks(){
        return  receivedchunks.size();
    }
    public int getTotalChunks(){
        return totalChunks;
    }
}
