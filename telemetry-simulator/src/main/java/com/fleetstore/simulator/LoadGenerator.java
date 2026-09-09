package com.fleetstore.simulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LoadGenerator {

    private static final int DRONE_COUNT = Integer.getInteger("fleet.droneCount", 10_000);
    private static final int SCHEDULER_THREADS = Integer.getInteger("fleet.schedulerThreads", 8);
    private static final long UPDATE_INTERVAL_MS = Long.getLong("fleet.updateIntervalMs", 1_000L);
    private static final String API_URL = System.getProperty(
            "fleet.apiUrl", "http://localhost:8080/api/telemetry/");

    public static void main(String[] args) {
        System.out.printf("Starting Synthetic Telemetry Engine for %,d drones...%n", DRONE_COUNT);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(SCHEDULER_THREADS);
        AtomicLong successfulPackets = new AtomicLong();
        AtomicLong failedPackets = new AtomicLong();
        AtomicInteger inFlight = new AtomicInteger();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        List<DroneState> drones = new ArrayList<>(DRONE_COUNT);
        for (int i = 0; i < DRONE_COUNT; i++) {
            drones.add(new DroneState("drone-" + i));
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            System.out.printf("Stopped. successful=%,d failed=%,d inFlight=%,d%n",
                    successfulPackets.get(), failedPackets.get(), inFlight.get());
        }));

        for (int i = 0; i < drones.size(); i++) {
            DroneState drone = drones.get(i);
            long initialDelay = (UPDATE_INTERVAL_MS * i) / Math.max(1, DRONE_COUNT);

            scheduler.scheduleAtFixedRate(
                    () -> sendTelemetry(client, drone, successfulPackets, failedPackets, inFlight),
                    initialDelay,
                    UPDATE_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private static void sendTelemetry(
            HttpClient client,
            DroneState drone,
            AtomicLong successfulPackets,
            AtomicLong failedPackets,
            AtomicInteger inFlight) {

        Telemetry telemetry = drone.nextTelemetry();
        String payload = String.format(
                "{\"sequence\":%d,\"lat\":%.2f,\"lng\":%.2f,\"bat\":%d}",
                telemetry.sequence(), telemetry.x(), telemetry.y(), telemetry.battery());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL + drone.vehicleId()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        inFlight.incrementAndGet();
        CompletableFuture<HttpResponse<Void>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.discarding());

        future.whenComplete((response, error) -> {
            inFlight.decrementAndGet();
            if (error == null && response.statusCode() >= 200 && response.statusCode() < 300) {
                long count = successfulPackets.incrementAndGet();
                if (count % 10_000 == 0) {
                    System.out.printf("%,d successful packets; failed=%,d; inFlight=%,d%n",
                            count, failedPackets.get(), inFlight.get());
                }
            } else {
                long failures = failedPackets.incrementAndGet();
                if (failures <= 10 || failures % 1_000 == 0) {
                    String reason = error != null ? error.getClass().getSimpleName()
                            : "HTTP " + response.statusCode();
                    System.err.printf("Telemetry failure #%d for %s: %s%n",
                            failures, drone.vehicleId(), reason);
                }
            }
        });
    }

    static final class DroneState {
        private final String vehicleId;
        private long sequence;
        private double x;
        private double y;

        DroneState(String vehicleId) {
            this.vehicleId = vehicleId;
            this.sequence = 0;
            this.x = ThreadLocalRandom.current().nextDouble(0, 1000);
            this.y = ThreadLocalRandom.current().nextDouble(0, 1000);
        }

        synchronized Telemetry nextTelemetry() {
            x += ThreadLocalRandom.current().nextDouble(-2, 2);
            y += ThreadLocalRandom.current().nextDouble(-2, 2);
            sequence++;
            return new Telemetry(sequence, x, y, ThreadLocalRandom.current().nextInt(10, 101));
        }

        String vehicleId() {
            return vehicleId;
        }
    }

    record Telemetry(long sequence, double x, double y, int battery) {}
}
