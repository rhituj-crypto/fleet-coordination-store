package com.fleetstore.simulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadGenerator {

    private static final int DRONE_COUNT = 10000;
    private static final int THREAD_POOL_SIZE = 50;
    private static final String API_URL = "http://localhost:8080/api/telemetry/";

    public static void main(String[] args) {
        System.out.println("Starting Synthetic Telemetry Engine...");
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        AtomicInteger packetsSent = new AtomicInteger(0);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        for (int i = 0; i < DRONE_COUNT; i++) {
            final String vehicleId = "drone-" + i;
            
            executor.submit(() -> {
                double x = ThreadLocalRandom.current().nextDouble(0, 1000);
                double y = ThreadLocalRandom.current().nextDouble(0, 1000);
                
                while (true) {
                    try {
                        x += ThreadLocalRandom.current().nextDouble(-2, 2);
                        y += ThreadLocalRandom.current().nextDouble(-2, 2);
                        
                        String payload = String.format("{\"lat\": %.2f, \"lng\": %.2f, \"bat\": %d}", 
                                                       x, y, ThreadLocalRandom.current().nextInt(10, 100));
                        
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(API_URL + vehicleId))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(payload))
                                .build();
                                
                        client.send(request, HttpResponse.BodyHandlers.discarding());
                        
                        int count = packetsSent.incrementAndGet();
                        if (count % 1000 == 0) {
                            System.out.println(count + " telemetry packets processed...");
                        }
                        
                        Thread.sleep(1000);
                        
                    } catch (Exception e) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                    }
                }
            });
        }
    }
}