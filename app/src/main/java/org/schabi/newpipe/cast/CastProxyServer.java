package org.schabi.newpipe.cast;

import java.io.BufferedReader;
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

/**
 * A tiny web server that runs on the phone for as long as it is casting. The Chromecast asks it
 * for the video, and it fetches the video from the real address and passes it on, adding the
 * headers that the Chromecast's receiver needs (CORS) and supporting partial requests (Range).
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

    private final String originUrl;
    private final CastClient.Logger logger;
    private final ServerSocket serverSocket;
    private final String secret;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private CastProxyServer(final String originUrl, final CastClient.Logger logger,
                            final ServerSocket serverSocket, final String secret) {
        this.originUrl = originUrl;
        this.logger = logger;
        this.serverSocket = serverSocket;
        this.secret = secret;
    }

    /**
     * Starts the server.
     *
     * @param originUrl the real address of the video
     * @param logger    receives a trace of the requests, may be null
     * @return the running server
     * @throws IOException if no port is available
     */
    static CastProxyServer start(final String originUrl, final CastClient.Logger logger)
            throws IOException {
        final byte[] random = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        final StringBuilder secret = new StringBuilder();
        for (final byte b : random) {
            secret.append(String.format(Locale.ROOT, "%02x", b));
        }

        final CastProxyServer server =
                new CastProxyServer(originUrl, logger, new ServerSocket(0), secret.toString());
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
        return "http://" + host + ":" + serverSocket.getLocalPort() + "/" + secret + ".mp4";
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
                writeHead(out, 204, "No Content", null, null, null, null);
            } else if (!path.equals(getPath())) {
                writeHead(out, 404, "Not Found", null, null, null, null);
            } else if ("GET".equals(method) || "HEAD".equals(method)) {
                relay(out, method, range);
            } else {
                writeHead(out, 405, "Method Not Allowed", null, null, null, null);
            }
            out.flush();
        } catch (final IOException e) {
            log("proxy: " + e);
        }
    }

    private void relay(final OutputStream out, final String method, final String range)
            throws IOException {
        final HttpURLConnection origin = (HttpURLConnection) new URL(originUrl).openConnection();
        try {
            origin.setRequestMethod(method);
            origin.setConnectTimeout(CONNECT_TIMEOUT_MS);
            origin.setReadTimeout(READ_TIMEOUT_MS);
            if (range != null) {
                origin.setRequestProperty("Range", range);
            }

            final int status = origin.getResponseCode();
            final String message = origin.getResponseMessage();
            log("proxy -> " + status + " " + message + " ("
                    + origin.getHeaderField("Content-Length") + " bytes)");
            writeHead(out, status, message == null ? "" : message,
                    origin.getHeaderField("Content-Type"),
                    origin.getHeaderField("Content-Length"),
                    origin.getHeaderField("Content-Range"),
                    origin.getHeaderField("Accept-Ranges"));

            if ("GET".equals(method)) {
                final InputStream body = status >= HttpURLConnection.HTTP_BAD_REQUEST
                        ? origin.getErrorStream() : origin.getInputStream();
                if (body != null) {
                    final byte[] buffer = new byte[BUFFER_SIZE];
                    int read = body.read(buffer);
                    while (read >= 0) {
                        out.write(buffer, 0, read);
                        read = body.read(buffer);
                    }
                    body.close();
                }
            }
        } finally {
            origin.disconnect();
        }
    }

    private static void writeHead(final OutputStream out, final int status, final String message,
                                  final String contentType, final String contentLength,
                                  final String contentRange, final String acceptRanges)
            throws IOException {
        final StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(message).append("\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        head.append("Access-Control-Allow-Headers: Range, Content-Type\r\n");
        head.append("Access-Control-Expose-Headers: Content-Length, Content-Range,"
                + " Accept-Ranges\r\n");
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (contentLength != null) {
            head.append("Content-Length: ").append(contentLength).append("\r\n");
        } else if (status == 204 || status == 404 || status == 405) {
            head.append("Content-Length: 0\r\n");
        }
        if (contentRange != null) {
            head.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        head.append("Accept-Ranges: ")
                .append(acceptRanges == null ? "bytes" : acceptRanges).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }
}
