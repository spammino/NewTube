package org.schabi.newpipe.cast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays a media URL on a Chromecast using Google's "Default Media Receiver", which only needs
 * the address of the video (or of a DASH/HLS manifest) and fetches it by itself.
 * <p>
 * All the methods block until the Chromecast answers, so they must not be called on the main
 * thread. {@link Listener} callbacks come from the connection's reader thread.
 * </p>
 */
public final class CastClient implements Closeable {

    /** The id of Google's Default Media Receiver, which exists on every Chromecast. */
    private static final String DEFAULT_MEDIA_RECEIVER_ID = "CC1AD845";

    private static final long REQUEST_TIMEOUT_MS = 10_000;
    private static final long LAUNCH_TIMEOUT_MS = 20_000;
    private static final long LOAD_TIMEOUT_MS = 30_000;

    private static final String TYPE_CONNECTION_CLOSED = "CONNECTION_CLOSED";

    /** What the receiver says about the media that is currently loaded. */
    public static final class MediaStatus {
        public final int mediaSessionId;
        /** IDLE, BUFFERING, PLAYING or PAUSED. */
        public final String playerState;
        /** Playback position in seconds. */
        public final double currentTime;
        /** Duration in seconds, or -1 if unknown. */
        public final double duration;
        /** FINISHED, CANCELLED, INTERRUPTED or ERROR when {@code playerState} is IDLE. */
        public final String idleReason;

        MediaStatus(final int mediaSessionId, final String playerState, final double currentTime,
                    final double duration, final String idleReason) {
            this.mediaSessionId = mediaSessionId;
            this.playerState = playerState;
            this.currentTime = currentTime;
            this.duration = duration;
            this.idleReason = idleReason;
        }

        @Override
        public String toString() {
            return playerState + " @" + currentTime + "s"
                    + (idleReason.isEmpty() ? "" : " (" + idleReason + ")");
        }
    }

    public interface Listener {
        /**
         * Called on the reader thread whenever the Chromecast reports a playback change.
         *
         * @param status the new state of the playback
         */
        void onMediaStatus(MediaStatus status);

        /**
         * Called once when the connection ends.
         *
         * @param reason why it ended, or {@code null} if it was closed locally
         */
        void onClosed(IOException reason);
    }

    /** Receives a readable trace of the messages exchanged with the Chromecast. */
    public interface Logger {
        /**
         * Called for every message that is sent or received (except the heartbeat).
         *
         * @param line a one-line description of the message
         */
        void log(String line);
    }

    private final Listener listener;
    private final AtomicInteger requestIds = new AtomicInteger(0);
    private final Map<Integer, BlockingQueue<JSONObject>> pending = new ConcurrentHashMap<>();
    private final Object applicationLock = new Object();

    private volatile CastConnection connection;
    private volatile Logger logger;
    private volatile boolean richMessages;
    private volatile boolean closed;
    private volatile int mediaSessionId = -1;
    // guarded by applicationLock
    private String transportId;
    private String sessionId;

    private CastClient(final Listener listener) {
        this.listener = listener;
    }

    /**
     * Connects to a Chromecast. The address must be one of the local network.
     *
     * @param host     the address of the Chromecast
     * @param port     the port of the Chromecast, usually 8009
     * @param listener receives the playback updates
     * @return the connected client
     */
    public static CastClient connect(final String host, final int port, final Listener listener)
            throws IOException {
        return connect(host, port, listener, null, false);
    }

    /**
     * Connects to a Chromecast, with a trace of the messages.
     *
     * @param host         the address of the Chromecast
     * @param port         the port of the Chromecast, usually 8009
     * @param listener     receives the playback updates
     * @param logger       receives a trace of the messages, may be null
     * @param richMessages whether to send the same extra fields that other Cast senders send
     *                     (a more detailed CONNECT, and empty metadata in LOAD)
     * @return the connected client
     */
    public static CastClient connect(final String host, final int port, final Listener listener,
                                     final Logger logger, final boolean richMessages)
            throws IOException {
        final CastClient client = new CastClient(listener);
        client.logger = logger;
        client.richMessages = richMessages;
        client.connection = CastConnection.open(host, port, new CastConnection.Listener() {
            @Override
            public void onMessage(final CastMessage message) {
                client.handleMessage(message);
            }

            @Override
            public void onClosed(final IOException reason) {
                client.handleClosed(reason);
            }
        });
        try {
            // opens the "virtual connection" with the Chromecast itself
            final JSONObject connect = client.connectMessage();
            client.log("-> connection to " + CastConnection.PLATFORM_ID + " " + connect);
            client.connection.send(CastConnection.SENDER_ID, CastConnection.PLATFORM_ID,
                    CastConnection.NS_CONNECTION, connect);
        } catch (final IOException e) {
            client.close();
            throw e;
        }
        return client;
    }

    /**
     * Returns the address of the phone on the network of the Chromecast, which is the address
     * that the Chromecast can use to reach the phone.
     *
     * @return the local address of the connection
     */
    public InetAddress getLocalAddress() {
        return connection.getLocalAddress();
    }

    /** Starts the Default Media Receiver and connects to it. */
    public void launchDefaultReceiver() throws IOException {
        request(CastConnection.PLATFORM_ID, CastConnection.NS_RECEIVER,
                json("type", "LAUNCH", "appId", DEFAULT_MEDIA_RECEIVER_ID), REQUEST_TIMEOUT_MS);

        final String transport;
        synchronized (applicationLock) {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    LAUNCH_TIMEOUT_MS);
            while (transportId == null) {
                final long remainingMs =
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0 || closed) {
                    throw new IOException("The Chromecast did not start the media receiver");
                }
                try {
                    applicationLock.wait(Math.min(remainingMs, 500));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
            }
            transport = transportId;
        }
        final JSONObject connect = connectMessage();
        log("-> connection to " + transport + " " + connect);
        connection.send(CastConnection.SENDER_ID, transport, CastConnection.NS_CONNECTION,
                connect);
    }

    /**
     * Starts playing a video.
     *
     * @param url          address of the video or of a DASH/HLS manifest, reachable by the
     *                     Chromecast
     * @param contentType  e.g. "video/mp4" or "application/dash+xml"
     * @param autoplay     whether to start playing as soon as it is loaded
     * @param startSeconds where to start from
     * @return the first status reported by the Chromecast, or {@code null} if it reported none
     */
    public MediaStatus load(final String url, final String contentType, final boolean autoplay,
                            final double startSeconds) throws IOException {
        final String transport;
        final String session;
        synchronized (applicationLock) {
            transport = transportId;
            session = sessionId;
        }
        if (transport == null) {
            throw new IOException("The media receiver is not running");
        }
        final JSONObject media = json("contentId", url, "contentType", contentType,
                "streamType", "BUFFERED");
        final JSONObject payload = json("type", "LOAD", "sessionId", session,
                "autoplay", autoplay, "currentTime", startSeconds, "media", media);
        if (richMessages) {
            put(media, "metadata", new JSONObject());
            put(payload, "customData", new JSONObject());
        }
        final JSONObject response =
                request(transport, CastConnection.NS_MEDIA, payload, LOAD_TIMEOUT_MS);
        return parseMediaStatus(response);
    }

    public void play() throws IOException {
        mediaRequest("PLAY", null);
    }

    public void pause() throws IOException {
        mediaRequest("PAUSE", null);
    }

    public void seek(final double seconds) throws IOException {
        mediaRequest("SEEK", seconds);
    }

    /** Stops the playback of the current media (the receiver stays open). */
    public void stop() throws IOException {
        mediaRequest("STOP", null);
    }

    /**
     * Sets the volume of the Chromecast.
     *
     * @param level the volume, from 0.0 to 1.0
     */
    public void setVolume(final double level) throws IOException {
        request(CastConnection.PLATFORM_ID, CastConnection.NS_RECEIVER,
                json("type", "SET_VOLUME", "volume", json("level", level)), REQUEST_TIMEOUT_MS);
    }

    /** Closes the receiver on the Chromecast, which goes back to its home screen. */
    public void stopReceiver() {
        final String session;
        synchronized (applicationLock) {
            session = sessionId;
        }
        if (session == null || connection == null) {
            return;
        }
        try {
            connection.send(CastConnection.SENDER_ID, CastConnection.PLATFORM_ID,
                    CastConnection.NS_RECEIVER,
                    json("type", "STOP", "sessionId", session,
                            "requestId", requestIds.incrementAndGet()));
        } catch (final IOException ignored) {
            // the connection is gone anyway
        }
    }

    @Override
    public void close() {
        final CastConnection current = connection;
        if (current != null) {
            current.close();
        }
    }

    // ------------------------------------------------------------------------------------------

    private void mediaRequest(final String type, final Double seconds) throws IOException {
        final String transport;
        synchronized (applicationLock) {
            transport = transportId;
        }
        final int mediaSession = mediaSessionId;
        if (transport == null || mediaSession < 0) {
            throw new IOException("Nothing is loaded on the Chromecast");
        }
        final JSONObject payload = json("type", type, "mediaSessionId", mediaSession);
        if (seconds != null) {
            put(payload, "currentTime", seconds);
        }
        request(transport, CastConnection.NS_MEDIA, payload, REQUEST_TIMEOUT_MS);
    }

    /**
     * Sends a request and waits for the answer that has the same request id.
     *
     * @param destinationId where the request goes (the platform or the media receiver)
     * @param namespace     the protocol namespace of the request
     * @param payload       the request, to which the request id is added
     * @param timeoutMs     how long to wait for the answer
     * @return the answer of the Chromecast
     */
    private JSONObject request(final String destinationId, final String namespace,
                               final JSONObject payload, final long timeoutMs)
            throws IOException {
        final int id = requestIds.incrementAndGet();
        final BlockingQueue<JSONObject> answer = new ArrayBlockingQueue<>(1);
        pending.put(id, answer);
        try {
            put(payload, "requestId", id);
            log("-> " + shortName(namespace) + " to " + destinationId + " " + payload);
            connection.send(CastConnection.SENDER_ID, destinationId, namespace, payload);
            final JSONObject response = answer.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (response == null) {
                throw new IOException("No answer from the Chromecast to "
                        + payload.optString("type"));
            }
            throwIfError(response);
            return response;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            pending.remove(id);
        }
    }

    private static void throwIfError(final JSONObject response) throws IOException {
        final String type = response.optString("type");
        switch (type) {
            case "LOAD_FAILED":
            case "LOAD_CANCELLED":
            case "INVALID_REQUEST":
            case "INVALID_PLAYER_STATE":
            case "LAUNCH_ERROR":
            case "ERROR":
            case TYPE_CONNECTION_CLOSED:
                final String reason = response.optString("reason");
                throw new IOException(type + (reason.isEmpty() ? "" : ": " + reason));
            default:
                break;
        }
    }

    private void handleMessage(final CastMessage message) {
        log("<- " + shortName(message.namespace) + " from " + message.sourceId + " "
                + message.payload);
        final JSONObject json;
        try {
            json = new JSONObject(message.payload);
        } catch (final JSONException e) {
            return;
        }

        final String type = json.optString("type");
        if (CastConnection.NS_RECEIVER.equals(message.namespace)
                && "RECEIVER_STATUS".equals(type)) {
            updateApplication(json);
        } else if (CastConnection.NS_MEDIA.equals(message.namespace)
                && "MEDIA_STATUS".equals(type)) {
            final MediaStatus status = parseMediaStatus(json);
            if (status != null) {
                mediaSessionId = status.mediaSessionId;
                listener.onMediaStatus(status);
            }
        } else if ("CLOSE".equals(type) && message.namespace.equals(
                CastConnection.NS_CONNECTION)) {
            // the receiver app was closed (e.g. someone cast something else)
            synchronized (applicationLock) {
                transportId = null;
                sessionId = null;
            }
            mediaSessionId = -1;
        }

        final int requestId = json.optInt("requestId", 0);
        if (requestId != 0) {
            final BlockingQueue<JSONObject> answer = pending.get(requestId);
            if (answer != null) {
                answer.offer(json);
            }
        }
    }

    private void updateApplication(final JSONObject receiverStatus) {
        final JSONObject status = receiverStatus.optJSONObject("status");
        final JSONArray applications =
                status == null ? null : status.optJSONArray("applications");

        String newTransport = null;
        String newSession = null;
        if (applications != null) {
            for (int i = 0; i < applications.length(); i++) {
                final JSONObject app = applications.optJSONObject(i);
                if (app != null
                        && DEFAULT_MEDIA_RECEIVER_ID.equals(app.optString("appId"))
                        && !app.optString("transportId").isEmpty()) {
                    newTransport = app.optString("transportId");
                    newSession = app.optString("sessionId");
                    break;
                }
            }
        }

        synchronized (applicationLock) {
            transportId = newTransport;
            sessionId = newSession;
            applicationLock.notifyAll();
        }
        if (newTransport == null) {
            mediaSessionId = -1;
        }
    }

    private static MediaStatus parseMediaStatus(final JSONObject json) {
        final JSONArray statuses = json.optJSONArray("status");
        final JSONObject status = statuses == null ? null : statuses.optJSONObject(0);
        if (status == null) {
            return null;
        }
        final JSONObject media = status.optJSONObject("media");
        return new MediaStatus(
                status.optInt("mediaSessionId", -1),
                status.optString("playerState"),
                status.optDouble("currentTime", 0),
                media == null ? -1 : media.optDouble("duration", -1),
                status.optString("idleReason"));
    }

    private JSONObject connectMessage() {
        final JSONObject message = json("type", "CONNECT");
        if (richMessages) {
            put(message, "origin", new JSONObject());
            put(message, "userAgent", "NewTube");
            put(message, "senderInfo", json("sdkType", 2, "version", "15.605.1.3",
                    "browserVersion", "44.0.2403.30", "platform", 4,
                    "systemVersion", "Android", "connectionType", 1));
        }
        return message;
    }

    private void log(final String line) {
        final Logger current = logger;
        if (current != null) {
            current.log(line);
        }
    }

    private static String shortName(final String namespace) {
        return namespace.substring(namespace.lastIndexOf('.') + 1);
    }

    private void handleClosed(final IOException reason) {
        closed = true;
        // wake up everyone who is waiting for an answer
        for (final BlockingQueue<JSONObject> answer : pending.values()) {
            answer.offer(json("type", TYPE_CONNECTION_CLOSED, "reason",
                    reason == null ? "closed" : String.valueOf(reason.getMessage())));
        }
        synchronized (applicationLock) {
            applicationLock.notifyAll();
        }
        listener.onClosed(reason);
    }

    /**
     * Builds a JSON object from alternating keys and values.
     *
     * @param keysAndValues key1, value1, key2, value2...
     * @return the JSON object
     */
    private static JSONObject json(final Object... keysAndValues) {
        final JSONObject object = new JSONObject();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            put(object, (String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return object;
    }

    private static void put(final JSONObject object, final String key, final Object value) {
        try {
            object.put(key, value);
        } catch (final JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
