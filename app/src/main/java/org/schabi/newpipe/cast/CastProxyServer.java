package org.schabi.newpipe.cast;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny web server that runs on the phone for as long as it is casting. The Chromecast asks it
 * for the video, and it takes the video either from a real address, which it fetches and passes
 * on, or from memory. It adds the headers that the Chromecast's receiver needs (CORS) and
 * supports partial requests (Range).
 * <p>
 * It only answers to devices of the local network, and only for an address that contains a random
 * secret, which is known only to the Chromecast it is given to.
 * </p>
 */
final class CastProxyServer {

    private static final int HEADER_LIMIT = 16 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int SECRET_BYTES = 12;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    /** Where the content comes from. */
    private interface Source {
        /**
         * Opens the content.
         *
         * @param method either GET or HEAD
         * @param range  the value of the Range header, or null
         * @return the answer, to be closed by the caller
         * @throws IOException if the content cannot be reached
         */
        Answer open(String method, String range) throws IOException;
    }

    /** The answer of a {@link Source}: the head of the HTTP response and, for GET, the body. */
    private static final class Answer implements Closeable {
        private final int status;
        private final String message;
        private final String contentType;
        private final String contentLength;
        private final String contentRange;
        private final String acceptRanges;
        private InputStream body;
        private Runnable onClose;

        private Answer(final int status, final String message, final String contentType,
                       final String contentLength, final String contentRange,
                       final String acceptRanges) {
            this.status = status;
            this.message = message == null ? "" : message;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.contentRange = contentRange;
            this.acceptRanges = acceptRanges;
        }

        private Answer withBody(final InputStream newBody, final Runnable newOnClose) {
            this.body = newBody;
            this.onClose = newOnClose;
            return this;
        }

        @Override
        public void close() throws IOException {
            try {
                if (body != null) {
                    body.close();
                }
            } finally {
                if (onClose != null) {
                    onClose.run();
                }
            }
        }
    }

    private final Source source;
    private final CastClient.Logger logger;
    private final ServerSocket serverSocket;
    private final String secret;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private CastProxyServer(final Source source, final CastClient.Logger logger,
                            final ServerSocket serverSocket, final String secret) {
        this.source = source;
        this.logger = logger;
        this.serverSocket = serverSocket;
        this.secret = secret;
    }

    /**
     * Starts a server that passes on a video found at a real address.
     *
     * @param originUrl the real address of the video
     * @param logger    receives a trace of the requests, may be null
     * @return the running server
     * @throws IOException if no port is available
     */
    static CastProxyServer start(final String originUrl, final CastClient.Logger logger)
            throws IOException {
        return start(new UrlSource(originUrl), logger);
    }

    /**
     * Starts a server that serves a video that is already in memory.
     *
     * @param data        the content of the video
     * @param contentType the type of the video, e.g. "video/mp4"
     * @param logger      receives a trace of the requests, may be null
     * @return the running server
     * @throws IOException if no port is available
     */
    static CastProxyServer start(final byte[] data, final String contentType,
                                 final CastClient.Logger logger) throws IOException {
        return start(new BytesSource(data, contentType), logger);
    }

    private static CastProxyServer start(final Source source, final CastClient.Logger logger)
            throws IOException {
        final byte[] random = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        final StringBuilder secret = new StringBuilder();
        for (final byte b : random) {
            secret.append(String.format(Locale.ROOT, "%02x", b));
        }

        final CastProxyServer server =
                new CastProxyServer(source, logger, new ServerSocket(0), secret.toString());
        final Thread acceptor = new Thread(server::acceptLoop, "cast-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
        return server;
    }

    /**
     * Returns the address that the Chromecast must use.
     *
     * @param phoneAddress the address of the phone, as seen by the Chromecast
     * @return the address of the video on this server
     */
    String getUrl(final InetAddress phoneAddress) {
        String host = phoneAddress.getHostAddress();
        final int scope = host.indexOf('%');
        if (scope >= 0) {
            host = host.substring(0, scope);
        }
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + serverSocket.getLocalPort() + getPath();
    }

    int getPort() {
        return serverSocket.getLocalPort();
    }

    String getPath() {
        return "/" + secret + ".mp4";
    }

    void stop() {
        if (closed.compareAndSet(false, true)) {
            try {
                serverSocket.close();
            } catch (final IOException ignored) {
                // already closed
            }
        }
    }

    private void log(final String line) {
        if (logger != null) {
            logger.log(line);
        }
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                final Socket client = serverSocket.accept();
                final Thread handler = new Thread(() -> handle(client), "cast-proxy-client");
                handler.setDaemon(true);
                handler.start();
            } catch (final IOException e) {
                return; // the server was stopped
            }
        }
    }

    private void handle(final Socket client) {
        try (Socket socket = client) {
            if (!CastConnection.isLocalAddress(socket.getInetAddress())) {
                return;
            }
            socket.setSoTimeout(READ_TIMEOUT_MS);
            final BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            final OutputStream out = socket.getOutputStream();

            final String requestLine = in.readLine();
            if (requestLine == null) {
                return;
            }
            String range = null;
            int size = requestLine.length();
            String line = in.readLine();
            while (line != null && !line.isEmpty() && size < HEADER_LIMIT) {
                size += line.length();
                if (line.regionMatches(true, 0, "Range:", 0, "Range:".length())) {
                    range = line.substring("Range:".length()).trim();
                }
                line = in.readLine();
            }

            final String[] parts = requestLine.split(" ");
            final String method = parts.length > 0 ? parts[0] : "";
            final String path = parts.length > 1 ? parts[1] : "";
            log("proxy <- " + requestLine + (range == null ? "" : " [Range: " + range + "]"));

            if ("OPTIONS".equals(method)) {
                writeHead(out, new Answer(204, "No Content", null, null, null, null));
            } else if (!path.equals(getPath())) {
                writeHead(out, new Answer(404, "Not Found", null, null, null, null));
            } else if ("GET".equals(method) || "HEAD".equals(method)) {
                relay(out, method, range);
            } else {
                writeHead(out, new Answer(405, "Method Not Allowed", null, null, null, null));
            }
            out.flush();
        } catch (final IOException e) {
            log("proxy: " + e);
        }
    }

    private void relay(final OutputStream out, final String method, final String range)
            throws IOException {
        try (Answer answer = source.open(method, range)) {
            log("proxy -> " + answer.status + " " + answer.message + " ("
                    + answer.contentLength + " bytes)");
            writeHead(out, answer);
            if ("GET".equals(method) && answer.body != null) {
                final byte[] buffer = new byte[BUFFER_SIZE];
                int read = answer.body.read(buffer);
                while (read >= 0) {
                    out.write(buffer, 0, read);
                    read = answer.body.read(buffer);
                }
            }
        }
    }

    private static void writeHead(final OutputStream out, final Answer answer)
            throws IOException {
        final StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(answer.status).append(' ').append(answer.message)
                .append("\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        head.append("Access-Control-Allow-Headers: Range, Content-Type\r\n");
        head.append("Access-Control-Expose-Headers: Content-Length, Content-Range,"
                + " Accept-Ranges\r\n");
        if (answer.contentType != null) {
            head.append("Content-Type: ").append(answer.contentType).append("\r\n");
        }
        if (answer.contentLength != null) {
            head.append("Content-Length: ").append(answer.contentLength).append("\r\n");
        } else if (answer.status == 204 || answer.status == 404 || answer.status == 405) {
            head.append("Content-Length: 0\r\n");
        }
        if (answer.contentRange != null) {
            head.append("Content-Range: ").append(answer.contentRange).append("\r\n");
        }
        head.append("Accept-Ranges: ")
                .append(answer.acceptRanges == null ? "bytes" : answer.acceptRanges)
                .append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Fetches the content from a real address. */
    private static final class UrlSource implements Source {
        private final String url;

        private UrlSource(final String url) {
            this.url = url;
        }

        @Override
        public Answer open(final String method, final String range) throws IOException {
            final HttpURLConnection origin = (HttpURLConnection) new URL(url).openConnection();
            try {
                origin.setRequestMethod(method);
                origin.setConnectTimeout(CONNECT_TIMEOUT_MS);
                origin.setReadTimeout(READ_TIMEOUT_MS);
                if (range != null) {
                    origin.setRequestProperty("Range", range);
                }
                final int status = origin.getResponseCode();
                InputStream body = null;
                if ("GET".equals(method)) {
                    body = status >= HttpURLConnection.HTTP_BAD_REQUEST
                            ? origin.getErrorStream() : origin.getInputStream();
                }
                return new Answer(status, origin.getResponseMessage(),
                        origin.getHeaderField("Content-Type"),
                        origin.getHeaderField("Content-Length"),
                        origin.getHeaderField("Content-Range"),
                        origin.getHeaderField("Accept-Ranges"))
                        .withBody(body, origin::disconnect);
            } catch (final IOException e) {
                origin.disconnect();
                throw e;
            }
        }
    }

    /** Serves content that is already in memory. */
    private static final class BytesSource implements Source {
        private final byte[] data;
        private final String contentType;

        private BytesSource(final byte[] data, final String contentType) {
            this.data = data;
            this.contentType = contentType;
        }

        @Override
        public Answer open(final String method, final String range) {
            final int length = data.length;
            int start = 0;
            int end = length - 1;
            boolean partial = false;

            final Matcher matcher = range == null ? null : RANGE.matcher(range);
            if (matcher != null && matcher.matches()) {
                partial = true;
                final String first = matcher.group(1);
                final String last = matcher.group(2);
                if (first.isEmpty() && !last.isEmpty()) { // the last N bytes
                    start = Math.max(0, length - Integer.parseInt(last));
                } else if (!first.isEmpty()) {
                    start = Integer.parseInt(first);
                    if (!last.isEmpty()) {
                        end = Math.min(end, Integer.parseInt(last));
                    }
                }
                if (start > end) {
                    return new Answer(416, "Range Not Satisfiable", contentType, "0",
                            "bytes */" + length, "bytes");
                }
            }

            final int count = end - start + 1;
            final InputStream body = "GET".equals(method)
                    ? new ByteArrayInputStream(data, start, count) : null;
            return new Answer(partial ? 206 : 200, partial ? "Partial Content" : "OK",
                    contentType, String.valueOf(count),
                    partial ? "bytes " + start + "-" + end + "/" + length : null, "bytes")
                    .withBody(body, null);
        }
    }
}
