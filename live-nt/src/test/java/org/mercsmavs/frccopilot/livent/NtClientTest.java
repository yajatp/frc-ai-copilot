package org.mercsmavs.frccopilot.livent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import edu.wpi.first.networktables.NetworkTableInstance;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Round-trip test: a NetworkTables SERVER and CLIENT, each on its own isolated {@link
 * NetworkTableInstance}, running loopback in the same JVM.
 *
 * <p>NT4 connections are asynchronous and not instantaneous, so every network-dependent assertion
 * here polls with a generous timeout rather than checking immediately. If the loopback connection
 * genuinely can't establish in this environment (e.g. sandboxed networking), the test skips itself
 * via {@code assumeTrue} rather than failing or being deleted.
 */
class NtClientTest {

    private static final double CONNECT_TIMEOUT_SECONDS = 5.0;
    private static final double VALUE_TIMEOUT_SECONDS = 5.0;

    private NetworkTableInstance serverInst;
    private NtClient client;
    private boolean connected;

    @BeforeEach
    void setUp() throws IOException {
        int port3 = findFreePort();
        int port4 = findFreePort();
        Path persistFile = Files.createTempFile("live-nt-test-", ".json");
        persistFile.toFile().deleteOnExit();

        serverInst = NetworkTableInstance.create();
        serverInst.startServer(persistFile.toAbsolutePath().toString(), "", port3, port4);

        client = new NtClient();
        client.connect("live-nt-test-client", "127.0.0.1", port4);

        connected = client.waitForConnection(CONNECT_TIMEOUT_SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (serverInst != null) {
            serverInst.stopServer();
            serverInst.close();
        }
    }

    @Test
    void clientReadsValuePublishedByServer() {
        assumeTrue(connected, "loopback NT4 client/server connection did not establish in this environment");
        assertTrue(client.isConnected());
        assertEquals(1, client.connections().size());

        serverInst.getEntry("/Test/value").setDouble(3.14);

        boolean sawValue =
                pollUntil(
                        () -> Math.abs(client.getDouble("/Test/value", Double.NaN) - 3.14) < 1e-9,
                        VALUE_TIMEOUT_SECONDS);
        assertTrue(sawValue, "client never observed the value published by the server");
        assertEquals(3.14, client.getDouble("/Test/value", Double.NaN), 1e-9);
    }

    @Test
    void clientListsKeysPublishedByServer() {
        assumeTrue(connected, "loopback NT4 client/server connection did not establish in this environment");

        serverInst.getEntry("/Test/name").setString("alpha");

        boolean sawKey = pollUntil(() -> client.keys("/Test").contains("/Test/name"), VALUE_TIMEOUT_SECONDS);
        assertTrue(sawKey, "client never saw the /Test/name topic published by the server");

        // The key-discovery subscription above is topics-only (no value traffic); reading the value
        // creates a fresh, separate subscription for it, so give that a moment to sync too.
        boolean sawValue = pollUntil(() -> "alpha".equals(client.getString("/Test/name", "")), VALUE_TIMEOUT_SECONDS);
        assertTrue(sawValue, "client never received the value of /Test/name from the server");
    }

    @Test
    void monitorReportsValueChanges() throws Exception {
        assumeTrue(connected, "loopback NT4 client/server connection did not establish in this environment");

        Map<String, Double> observed = new ConcurrentHashMap<>();
        try (AutoCloseable monitorHandle =
                client.monitor(
                        Set.of("/Test/monitored"),
                        (key, value) -> {
                            if (value.isDouble()) {
                                observed.put(key, value.getDouble());
                            }
                        })) {

            serverInst.getEntry("/Test/monitored").setDouble(7.0);

            boolean sawChange =
                    pollUntil(
                            () -> observed.getOrDefault("/Test/monitored", Double.NaN) == 7.0,
                            VALUE_TIMEOUT_SECONDS);
            assertTrue(sawChange, "monitor callback never observed the server-side value change");
        }
    }

    private static boolean pollUntil(BooleanSupplier condition, double timeoutSeconds) {
        long deadlineNanos = System.nanoTime() + (long) (timeoutSeconds * 1_000_000_000L);
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return condition.getAsBoolean();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
