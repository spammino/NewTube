package org.schabi.newpipe.cast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A TLS connection to a Chromecast on the local network, speaking the Cast v2 wire protocol:
 * each message is a 4-byte big-endian length followed by a protobuf {@link CastMessage}.
 * <p>
 * It only talks to the device on the local network: no Google server and no account is involved.
 * The heartbeat (PING/PONG) is handled here; every other message is passed to the
 * {@link Listener}, on the reader thread.
 * </p>
 */
final class CastConnection implements Closeable {

    static final int DEFAULT_PORT = 8009;
    static final String SENDER_ID = "sender-0";
    static final String PLATFORM_ID = "receiver-0";
    static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final long PING_PERIOD_MS = 5000;
    private static final long SILENCE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final int MAX_MESSAGE_SIZE = 1 << 20;

    interface Listener {
        /**
         * A non-heartbeat message arrived. Called on the reader thread.
         *
         * @param message the message
         */
        void onMessage(CastMessage message);

        /**
         * The connection ended. Called once.
         *
         * @param reason why it ended, or {@code null} if it was closed locally
         */
        void onClosed(IOException reason);
    }

    private final Listener listener;
    private final SSLSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ScheduledExecutorService heartbeat;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile long lastReceivedNanos = System.nanoTime();

    private CastConnection(final SSLSocket socket, final Listener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "cast-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens a connection. Blocks, so it must not be called on the main thread.
     * Only addresses of the local network are accepted.
     *
     * @param host     the address of the Chromecast
     * @param port     the port of the Chromecast
     * @param listener receives the messages and the end of the connection
     * @return the open connection
     */
    static CastConnection open(final String host, final int port, final Listener listener)
            throws IOException {
        final InetAddress address = InetAddress.getByName(host);
        if (!isLocalAddress(address)) {
            throw new IOException("Refusing to connect to a non-local address: " + host);
        }

        final Socket plain = new Socket();
        final SSLSocket ssl;
        try {
            plain.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            ssl = (SSLSocket) createTrustAllContext().getSocketFactory()
                    .createSocket(plain, host, port, true);
            ssl.setSoTimeout(CONNECT_TIMEOUT_MS); // only for the handshake
            ssl.startHandshake();
            ssl.setSoTimeout(0); // afterwards the heartbeat watches for silence
        } catch (final IOException | GeneralSecurityException e) {
            plain.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }

        final CastConnection connection = new CastConnection(ssl, listener);
        connection.start();
        return connection;
    }

    private void start() {
        final Thread reader = new Thread(this::readLoop, "cast-reader");
        reader.setDaemon(true);
        reader.start();
        // the first PING comes after the caller had the chance to send its CONNECT message
        heartbeat.scheduleAtFixedRate(this::heartbeatTick, PING_PERIOD_MS, PING_PERIOD_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Sends a message with a JSON payload.
     *
     * @param sourceId      who sends the message
     * @param destinationId who should receive it
     * @param namespace     the protocol namespace of the message
     * @param payload       the content of the message
     */
    void send(final String sourceId, final String destinationId, final String namespace,
              final JSONObject payload) throws IOException {
        final byte[] data =
                new CastMessage(sourceId, destinationId, namespace, payload.toString()).encode();
        synchronized (out) {
            if (closed.get()) {
                throw new IOException("Connection closed");
            }
            out.writeInt(data.length);
            out.write(data);
            out.flush();
        }
    }

    /**
     * Returns the address of the phone on the network of the Chromecast.
     *
     * @return the local address of the connection
     */
    InetAddress getLocalAddress() {
        return socket.getLocalAddress();
    }

    @Override
    public void close() {
        shutdown(null);
    }

    private void heartbeatTick() {
        if (System.nanoTime() - lastReceivedNanos > SILENCE_TIMEOUT_NANOS) {
            shutdown(new IOException("The Chromecast stopped responding"));
            return;
        }
        try {
            send(SENDER_ID, PLATFORM_ID, NS_HEARTBEAT, json("PING"));
        } catch (final IOException e) {
            shutdown(e);
        }
    }

    private void readLoop() {
        IOException reason = null;
        try {
            while (!closed.get()) {
                final int length = in.readInt();
                if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                    throw new IOException("Invalid Cast message length: " + length);
                }
                final byte[] data = new byte[length];
                in.readFully(data);
                lastReceivedNanos = System.nanoTime();
                handle(CastMessage.decode(data));
            }
        } catch (final IOException e) {
            reason = e;
        }
        shutdown(reason);
    }

    private void handle(final CastMessage message) throws IOException {
        if (message.payload == null) {
            return; // binary messages are not used by the media receiver
        }
        if (NS_HEARTBEAT.equals(message.namespace)) {
            try {
                if ("PING".equals(new JSONObject(message.payload).optString("type"))) {
                    send(message.destinationId, message.sourceId, NS_HEARTBEAT, json("PONG"));
                }
            } catch (final JSONException ignored) {
                // malformed heartbeat: nothing to answer
            }
            return;
        }
        listener.onMessage(message);
    }

    private void shutdown(final IOException reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        heartbeat.shutdownNow();
        try {
            socket.close();
        } catch (final IOException ignored) {
            // already closing
        }
        listener.onClosed(reason);
    }

    private static JSONObject json(final String type) {
        try {
            return new JSONObject().put("type", type);
        } catch (final JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Tells whether an address belongs to the local network: loopback, private
     * (192.168.x.x, 10.x.x.x, 172.16-31.x.x) and link-local addresses.
     *
     * @param address the address to check
     * @return true if the address is a local one
     */
    static boolean isLocalAddress(final InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()) {
            return true;
        }
        final byte[] bytes = address.getAddress();
        // IPv6 unique local addresses (fc00::/7)
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * The Chromecast presents a self-signed device certificate, which cannot be checked like the
     * one of a website. This is why the connection is restricted to local addresses (see
     * {@link #isLocalAddress}) and why nothing sensitive is ever sent over it.
     *
     * @return a context that accepts any certificate
     */
    private static SSLContext createTrustAllContext() throws GeneralSecurityException {
        final TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trustAll}, null);
        return context;
    }
}
