package com.example.demo.pc.discovery;


import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;

@Service
public class PeerDiscoveryService {
    private static final String SERVICE_TYPE = "_fileshare._tcp.local.";
    private final PeerRegistry peerRegistry;
    @Value("${server.port}")
    private int ServerPort;
    @Value("${app.instance-name}")
    private String baseinstancename;
    private String instanceName;
    private JmDNS jmDNS;

    public PeerDiscoveryService(PeerRegistry peerRegistry) {
        this.peerRegistry = peerRegistry;
    }

    @PostConstruct
    public void start() {
        this.instanceName = baseinstancename + "-" + UUID.randomUUID().toString().substring(0, 6);
        try {
            jmDNS = JmDNS.create(InetAddress.getLocalHost());
            ServiceInfo serviceInfo = ServiceInfo.create(
                    SERVICE_TYPE,
                    instanceName,
                    ServerPort,
                    "P2P file share service"
            );
            jmDNS.registerService(serviceInfo);
            System.out.println("[JmDNS] Registered Service" + instanceName + "Server Port" + ServerPort);

            jmDNS.addServiceListener(
                    SERVICE_TYPE, new ServiceListener() {
                        @Override
                        public void serviceAdded(ServiceEvent event) {
                            jmDNS.requestServiceInfo(event.getType(), event.getName());
                        }

                        @Override
                        public void serviceRemoved(ServiceEvent event) {
                            String peername = event.getName();
                            peerRegistry.removepeer(peername);
                            System.out.println("[JmDNS] Peer Removed" + peername);
                        }

                        @Override
                        public void serviceResolved(ServiceEvent event) {
                            ServiceInfo info = event.getInfo();
                            if (info.getInet4Addresses().length == 0) return;
                            String peername = info.getName();
                            if (peername.equalsIgnoreCase(instanceName)) return;
                            String ip = info.getInet4Addresses()[0].getHostAddress();
                            int port = info.getPort();
                            peerRegistry.addpeer(peername, new PeerInfo(peername, ip, port));
                            System.out.println("[JmDNS] Peer Discovered" + peername + "@" + ip + ":" + port);
                        }
                    }
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start JmDNS discovery" + e);
        }
    }

    @PreDestroy
    public void stop() {
        if (jmDNS != null) {
            try {
                jmDNS.unregisterAllServices();
                jmDNS.close();
            } catch (IOException e) {
                System.err.println("Failed to close the JmDNS " + e);
            }
        }
    }
}
