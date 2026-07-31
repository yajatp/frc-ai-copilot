package org.mercsmavs.frccopilot.livent;

import edu.wpi.first.networktables.ConnectionInfo;
import edu.wpi.first.networktables.MultiSubscriber;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableEvent;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.NetworkTableValue;
import edu.wpi.first.networktables.PubSubOption;
import edu.wpi.first.networktables.TopicInfo;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * A thin wrapper around a NetworkTables 4 client, used to read live robot telemetry from the
 * copilot process.
 *
 * <p>Each {@code NtClient} owns its own {@link NetworkTableInstance} created via {@link
 * NetworkTableInstance#create()} &mdash; it never touches {@link NetworkTableInstance#getDefault()
 * the global default instance}. This is what lets a single JVM (e.g. a unit test) run an isolated
 * NT server and an isolated NT client side by side without them colliding.
 *
 * <p>This class is read/observe-only from the outside: the only way to write a value back onto
 * NetworkTables through it is the package-private {@link #publishDouble(String, double)}, which is
 * intentionally reachable only from {@link NtWriteGuard} in this same package. Anything that wants
 * to write to the robot MUST go through {@link NtWriteGuard#writeDouble(NtClient, String, double)}.
 */
public final class NtClient implements AutoCloseable {

    private final NetworkTableInstance inst;

    /**
     * NT4 only announces topics to a client for prefixes the client has subscribed to. This
     * standing, topics-only (no value traffic) subscription to everything is what lets {@link
     * #keys()} / {@link #keys(String)} discover topics the caller hasn't explicitly read yet -
     * mirroring how tools like OutlineViewer enumerate the whole table.
     */
    private final MultiSubscriber discoverySubscriber;

    private volatile boolean closed;

    /** Creates a client backed by a brand-new, isolated NetworkTables instance. */
    public NtClient() {
        this.inst = NetworkTableInstance.create();
        this.discoverySubscriber = new MultiSubscriber(inst, new String[] {""}, PubSubOption.topicsOnly(true));
    }

    /**
     * Starts an NT4 client and points it at a server. Safe to call again to change servers; per
     * ntcore semantics {@code startClient4} is a no-op if already started, and {@code setServer}
     * takes effect without restarting the client.
     *
     * @param identity network identity to advertise (must be non-empty)
     * @param host server hostname or IP address
     * @param port server port (use {@link NetworkTableInstance#kDefaultPort4} for the standard NT4
     *     port)
     */
    public void connect(String identity, String host, int port) {
        ensureOpen();
        inst.startClient4(identity);
        inst.setServer(host, port);
    }

    /** Same as {@link #connect(String, String, int)} using the default NT4 port. */
    public void connect(String identity, String host) {
        connect(identity, host, NetworkTableInstance.kDefaultPort4);
    }

    /** Returns whether the client currently has an established connection to a server. */
    public boolean isConnected() {
        ensureOpen();
        return inst.isConnected();
    }

    /** Returns information about current network connections (empty if not connected). */
    public List<ConnectionInfo> connections() {
        ensureOpen();
        return List.of(inst.getConnections());
    }

    /**
     * Blocks (polling) until the client is connected or the timeout elapses. Connections are not
     * instantaneous &mdash; NT4 handshakes take a moment &mdash; so callers (especially tests) should
     * use a generous timeout rather than assuming an immediate connection.
     *
     * @param timeoutSeconds maximum time to wait, in seconds
     * @return true if connected by the time this returns, false if the timeout elapsed first
     */
    public boolean waitForConnection(double timeoutSeconds) {
        ensureOpen();
        long deadlineNanos = System.nanoTime() + (long) (timeoutSeconds * 1_000_000_000L);
        while (!inst.isConnected()) {
            if (System.nanoTime() >= deadlineNanos) {
                return inst.isConnected();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return inst.isConnected();
            }
        }
        return true;
    }

    /**
     * Reads the current value of a double topic, by key.
     *
     * @param key topic key (leading slash optional; normalized internally)
     * @param defaultValue value to return if the key doesn't exist or isn't a double
     * @return the current value, or {@code defaultValue}
     */
    public double getDouble(String key, double defaultValue) {
        ensureOpen();
        return entry(key).getDouble(defaultValue);
    }

    /**
     * Reads the current value of a boolean topic, by key.
     *
     * @param key topic key (leading slash optional; normalized internally)
     * @param defaultValue value to return if the key doesn't exist or isn't a boolean
     * @return the current value, or {@code defaultValue}
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        ensureOpen();
        return entry(key).getBoolean(defaultValue);
    }

    /**
     * Reads the current value of a string topic, by key.
     *
     * @param key topic key (leading slash optional; normalized internally)
     * @param defaultValue value to return if the key doesn't exist or isn't a string
     * @return the current value, or {@code defaultValue}
     */
    public String getString(String key, String defaultValue) {
        ensureOpen();
        return entry(key).getString(defaultValue);
    }

    /**
     * Reads the raw current value (of any NT type) for a key.
     *
     * @param key topic key (leading slash optional; normalized internally)
     * @return the value, which reports {@link NetworkTableValue#isValid()} == false if the key does
     *     not currently exist
     */
    public NetworkTableValue getValue(String key) {
        ensureOpen();
        return entry(key).getValue();
    }

    /** Returns whether a key currently has a published value. */
    public boolean hasKey(String key) {
        ensureOpen();
        return entry(key).exists();
    }

    /** Lists every currently published topic key known to this instance. */
    public Set<String> keys() {
        return keys("");
    }

    /** Lists every currently published topic key starting with the given prefix. */
    public Set<String> keys(String prefix) {
        ensureOpen();
        Set<String> out = new TreeSet<>();
        for (TopicInfo info : inst.getTopicInfo(prefix)) {
            out.add(info.name);
        }
        return out;
    }

    /**
     * Watches for value changes on topics whose names start with any of the given prefixes, and
     * reports them via {@code onChange} as they arrive (including the initial/current value of each
     * matching topic, so callers see state immediately rather than only future deltas).
     *
     * <p>The callback runs on a background NT listener thread; keep it fast and thread-safe.
     *
     * @param prefixes topic-name prefixes to watch (an empty set watches everything)
     * @param onChange called with (topicName, newValue) for every matching value change
     * @return a handle whose {@link AutoCloseable#close()} stops the monitor
     */
    public AutoCloseable monitor(Set<String> prefixes, BiConsumer<String, NetworkTableValue> onChange) {
        ensureOpen();
        String[] prefixArray = prefixes.isEmpty() ? new String[] {""} : prefixes.toArray(new String[0]);
        int listenerHandle =
                inst.addListener(
                        prefixArray,
                        EnumSet.of(NetworkTableEvent.Kind.kValueAll, NetworkTableEvent.Kind.kImmediate),
                        event -> {
                            if (event.valueData != null) {
                                onChange.accept(event.valueData.getTopic().getName(), event.valueData.value);
                            }
                        });
        return () -> inst.removeListener(listenerHandle);
    }

    /**
     * Like {@link #monitor(Set, BiConsumer)}, but subscribes for <em>every</em> value the publisher
     * sends rather than whatever the latest happens to be each period.
     *
     * <p>This distinction matters for any analysis that counts occurrences instead of reading a
     * level. NT4's default subscription delivers periodic samples (100 ms), so a 50 Hz signal
     * arrives decimated ~5:1 — fine for a battery-voltage gauge, wrong for counting loop overruns
     * or catching a brief current spike, because the samples that were dropped are exactly the ones
     * that would have been anomalous.
     *
     * @param prefixes topic-name prefixes to watch (an empty set watches everything)
     * @param periodSeconds requested delivery period; the floor on how often batches are sent
     * @param onChange called with (topicName, newValue) for every matching value change
     * @return a handle whose {@link AutoCloseable#close()} stops the monitor and drops the
     *     subscription
     */
    public AutoCloseable monitorAll(
            Set<String> prefixes, double periodSeconds, BiConsumer<String, NetworkTableValue> onChange) {
        ensureOpen();
        String[] prefixArray = prefixes.isEmpty() ? new String[] {""} : prefixes.toArray(new String[0]);
        // The listener alone would inherit default (periodic, latest-only) subscription options, so
        // the sendAll subscription has to be held explicitly for as long as the monitor lives.
        MultiSubscriber subscriber =
                new MultiSubscriber(
                        inst, prefixArray, PubSubOption.sendAll(true), PubSubOption.periodic(periodSeconds));
        int listenerHandle =
                inst.addListener(
                        prefixArray,
                        EnumSet.of(NetworkTableEvent.Kind.kValueAll, NetworkTableEvent.Kind.kImmediate),
                        event -> {
                            if (event.valueData != null) {
                                onChange.accept(event.valueData.getTopic().getName(), event.valueData.value);
                            }
                        });
        return () -> {
            inst.removeListener(listenerHandle);
            subscriber.close();
        };
    }

    /**
     * Writes a double to a key. Package-private on purpose: this is the sole write path for this
     * client, and it is reachable only from {@link NtWriteGuard}, which enforces the safety
     * whitelist before ever calling this. Nothing outside this package should be able to write to
     * NetworkTables through an {@code NtClient}.
     */
    void publishDouble(String key, double value) {
        ensureOpen();
        entry(key).setDouble(value);
    }

    /** Direct access to the underlying isolated instance, for the write guard and tests only. */
    NetworkTableInstance instance() {
        return inst;
    }

    private NetworkTableEntry entry(String key) {
        return inst.getEntry(NetworkTable.normalizeKey(key, true));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("NtClient is closed");
        }
    }

    /** Stops the client and destroys its isolated NetworkTables instance. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            discoverySubscriber.close();
            inst.close();
        }
    }
}
