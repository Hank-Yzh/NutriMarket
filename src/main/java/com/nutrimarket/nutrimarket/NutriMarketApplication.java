package com.nutrimarket.nutrimarket;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class NutriMarketApplication {
    private static final Logger log = LoggerFactory.getLogger(NutriMarketApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(NutriMarketApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printIpAddress() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || networkInterface.isVirtual()) {
                    continue;
                }

                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        log.info("========================================");
                        log.info("NutriMarket Launch successful!");
                        log.info("Current LAN IP address: {}", address.getHostAddress());
                        log.info("========================================");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to obtain an IP address", e);
        }
    }

}
