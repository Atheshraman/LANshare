package com.example.demo.pc.config;

import com.example.demo.pc.service.TransferService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class cleanupScheduler {
    private final TransferService transferService;

    public cleanupScheduler(TransferService transferService) {
        this.transferService = transferService;
    }

    @Scheduled(fixedRate = 300000)
    public void cleanupExpiredTransfers() {
        int before = transferService.pendingCount();
        transferService.purgeExpired();
        int after = transferService.pendingCount();
        if (before != after) {
            System.out.println("[cleanup purged]" + (before - after) + " expired pending transfer(s)");
        }
    }
}
