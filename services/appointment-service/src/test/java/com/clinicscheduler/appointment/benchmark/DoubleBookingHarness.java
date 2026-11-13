package com.clinicscheduler.appointment.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual concurrency harness: fires N=50 simultaneous POST /api/appointments for the SAME slot,
 * ten distinct slots in sequence, against the real running stack (both services + MySQL).
 *
 * Expected per slot: exactly 1 successful booking, 49 conflicts (HTTP 409) and 1 appointment row.
 * See scripts/concurrency_test.sh for the one-command runner.
 */
public final class DoubleBookingHarness {

    private static final int CONCURRENCY = Integer.getInteger("harness.concurrency", 50);
    private static final int SLOTS = Integer.getInteger("harness.slots", 10);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final String APPOINTMENT_URL =
            env("APPOINTMENT_URL", "http://localhost:8082");
    private static final String PROVIDER_URL =
            env("PROVIDER_URL", "http://localhost:8081");
    private static final String DB_URL = env("APPOINTMENT_DB_URL",
            "jdbc:mysql://localhost:3306/appointment_db?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String DB_USER = env("DB_USER", "clinic");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "clinic_pass");

    private DoubleBookingHarness() {
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ExecutorService httpExecutor = Executors.newFixedThreadPool(64);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(httpExecutor)
                .build();

        printBanner();
        String runId = Long.toString(System.currentTimeMillis());
        String token = login(mapper, http);
        List<Slot> slots = findOpenSlots(mapper, http);
        if (slots.size() < SLOTS) {
            throw new IllegalStateException("Need " + SLOTS + " open slots, found " + slots.size());
        }

        int totalSuccess = 0;
        int totalConflict = 0;
        int totalOther = 0;
        int totalRows = 0;
        long runStarted = System.nanoTime();

        for (int slotIndex = 0; slotIndex < SLOTS; slotIndex++) {
            Slot slot = slots.get(slotIndex);
            System.out.printf(Locale.ROOT, "[%d/%d] racing %d bookings for slot %d...%n",
                    slotIndex + 1, SLOTS, CONCURRENCY, slot.id);
            System.out.flush();
            Outcome outcome = raceOneSlot(mapper, http, token, slot, runId);
            totalSuccess += outcome.success;
            totalConflict += outcome.conflicts;
            totalOther += outcome.other;
            totalRows += countRows(slot.id);
            System.out.printf(Locale.ROOT,
                    "slot %-7d provider=%-4d start=%s  success=%d conflicts=%d other=%d rows=%d elapsed=%dms%n",
                    slot.id, slot.providerId, slot.startTime, outcome.success, outcome.conflicts,
                    outcome.other, countRows(slot.id), outcome.elapsedMs);
            System.out.flush();
        }

        long elapsedMs = (System.nanoTime() - runStarted) / 1_000_000;

        System.out.println();
        System.out.println("=== aggregate over " + SLOTS + " slots, " + CONCURRENCY
                + " concurrent bookings per slot ===");
        System.out.printf(Locale.ROOT, "successful bookings : %d (expected %d)%n", totalSuccess, SLOTS);
        System.out.printf(Locale.ROOT, "409 conflicts       : %d (expected %d)%n",
                totalConflict, SLOTS * (CONCURRENCY - 1));
        System.out.printf(Locale.ROOT, "other errors        : %d (expected 0)%n", totalOther);
        System.out.printf(Locale.ROOT, "appointment rows    : %d (expected %d)%n", totalRows, SLOTS);
        System.out.printf(Locale.ROOT, "total wall time     : %dms%n", elapsedMs);
        boolean pass = totalSuccess == SLOTS
                && totalConflict == SLOTS * (CONCURRENCY - 1)
                && totalOther == 0
                && totalRows == SLOTS;
        System.out.println("invariant           : " + (pass ? "PASS - exactly one winner per slot" : "FAIL"));
        System.out.flush();

        httpExecutor.shutdownNow();
        System.exit(pass ? 0 : 1);
    }

    private static void printBanner() {
        System.out.println("=== ClinicScheduler double-booking harness ===");
        System.out.println("appointment-service : " + APPOINTMENT_URL);
        System.out.println("provider-service    : " + PROVIDER_URL);
        System.out.println("concurrency         : " + CONCURRENCY + " simultaneous requests per slot");
        System.out.println("slots               : " + SLOTS);
        System.out.println("date_utc            : " + java.time.Instant.now());
        System.out.println();
    }

    private static String login(ObjectMapper mapper, HttpClient http) throws Exception {
        ObjectNode body = mapper.createObjectNode()
                .put("email", "staff@clinic.test")
                .put("password", "staff123");
        HttpResponse<String> response = post(http, APPOINTMENT_URL + "/api/auth/login",
                mapper.writeValueAsString(body), null);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Login failed: HTTP " + response.statusCode()
                    + " " + response.body());
        }
        return mapper.readTree(response.body()).get("token").asText();
    }

    private static List<Slot> findOpenSlots(ObjectMapper mapper, HttpClient http) throws Exception {
        LocalDate fromDate = LocalDate.now().plusDays(1);
        LocalDate toDate = LocalDate.now().plusDays(30);
        String uri = PROVIDER_URL + "/api/availability/search?page=0&size=" + SLOTS
                + "&from=" + fromDate + "T00:00:00"
                + "&to=" + toDate + "T23:59:59";
        HttpResponse<String> response = get(http, uri);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Availability search failed: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        JsonNode content = mapper.readTree(response.body()).get("content");
        List<Slot> slots = new ArrayList<>();
        for (JsonNode node : content) {
            slots.add(new Slot(
                    node.get("id").asLong(),
                    node.get("providerId").asLong(),
                    LocalDateTime.parse(node.get("startTime").asText(), ISO),
                    LocalDateTime.parse(node.get("endTime").asText(), ISO)));
        }
        return slots;
    }

    private static Outcome raceOneSlot(ObjectMapper mapper, HttpClient http, String token,
                                       Slot slot, String runId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONCURRENCY);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < CONCURRENCY; i++) {
            int threadIndex = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    String patientId = "harness-" + runId + "-" + slot.id + "-" + threadIndex;
                    ObjectNode booking = mapper.createObjectNode()
                            .put("patientId", patientId)
                            .put("patientName", "Harness Patient " + threadIndex)
                            .put("providerId", slot.providerId)
                            .put("slotId", slot.id)
                            .put("startTime", slot.startTime.format(ISO))
                            .put("endTime", slot.endTime.format(ISO));
                    HttpResponse<String> response = post(http, APPOINTMENT_URL + "/api/appointments",
                            mapper.writeValueAsString(booking), token);
                    int code = response.statusCode();
                    if (code == 200 || code == 201) {
                        success.incrementAndGet();
                    } else if (code == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        System.out.println("  unexpected HTTP " + code + ": " + response.body());
                    }
                } catch (Exception ex) {
                    other.incrementAndGet();
                    System.out.println("  request error: " + ex);
                } finally {
                    finished.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        long started = System.nanoTime();
        start.countDown();
        if (!finished.await(60, TimeUnit.SECONDS)) {
            long stuck = finished.getCount();
            other.addAndGet((int) stuck);
            System.out.println("  timeout: " + stuck + " requests did not finish within 60s");
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        pool.shutdownNow();
        return new Outcome(success.get(), conflicts.get(), other.get(), elapsedMs);
    }

    private static int countRows(long slotId) throws Exception {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM appointments WHERE slot_id = ?")) {
            statement.setLong(1, slotId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static HttpResponse<String> post(HttpClient http, String uri, String body, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient http, String uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Slot(long id, long providerId, LocalDateTime startTime, LocalDateTime endTime) {
    }

    private record Outcome(int success, int conflicts, int other, long elapsedMs) {
    }
}
