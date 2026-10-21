package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;

/**
 * One request against a health check's endpoint, made the way Route 53's checkers make it. It
 * connects to {@code IPAddress} when set, resolving {@code FullyQualifiedDomainName} only without
 * one, and sends the domain name as the {@code Host} header and, for HTTPS, as the TLS server name.
 * The connection must open within four seconds, then for HTTP types a {@code GET} whose status must be 2xx or 3xx
 * within two seconds. Redirects are not followed and HTTPS certificates are not validated. The
 * string-match types also look for {@code SearchString} in the first 5,120 bytes of the body.
 */
final class Route53HealthProbe {

    record Result(boolean success, String status) {}

    static final int CONNECT_TIMEOUT_MS = 4000;
    static final int RESPONSE_TIMEOUT_MS = 2000;
    private static final int SEARCH_WINDOW_BYTES = 5120;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int HEADER_END = ('\r' << 24) | ('\n' << 16) | ('\r' << 8) | '\n';
    private static final String FIREWALL_HINT = " The endpoint or the internet connection is down, or requests"
            + " are being blocked by your firewall. See https://docs.aws.amazon.com/console/route53/health-checks-firewall-rules";

    private final SSLSocketFactory tls;

    Route53HealthProbe() {
        this.tls = trustingSocketFactory();
    }

    static boolean probes(String type) {
        return type != null && switch (type) {
            case "HTTP", "HTTPS", "HTTP_STR_MATCH", "HTTPS_STR_MATCH", "TCP" -> true;
            default -> false;
        };
    }

    Result probe(String healthCheckId, HealthCheckConfig cfg) {
        String type = cfg.getType();
        boolean https = type.startsWith("HTTPS");
        String host = cfg.getIpAddress() != null ? cfg.getIpAddress() : cfg.getFullyQualifiedDomainName();
        if (host == null || host.isBlank()) {
            return new Result(false, "Failure: The health check has no IPAddress or FullyQualifiedDomainName.");
        }
        Integer port = cfg.getPort() != null ? cfg.getPort() : switch (type) {
            case "HTTP", "HTTP_STR_MATCH" -> 80;
            case "HTTPS", "HTTPS_STR_MATCH" -> 443;
            default -> null;
        };
        if (port == null) {
            return new Result(false, "Failure: The health check has no Port.");
        }
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            } catch (UnknownHostException e) {
                return new Result(false, "Failure: DNS resolution failed for " + host + ".");
            } catch (SocketTimeoutException e) {
                return new Result(false, "Failure: Connection timed out." + FIREWALL_HINT);
            } catch (ConnectException e) {
                return new Result(false, "Failure: Connection refused." + FIREWALL_HINT);
            }
            if ("TCP".equals(type)) {
                return new Result(true, "Success: TCP connection established.");
            }
            socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
            String fqdn = cfg.getFullyQualifiedDomainName();
            Socket channel = https ? startTls(socket, host, port, fqdn, !Boolean.FALSE.equals(cfg.getEnableSni())) : socket;
            return exchange(channel, healthCheckId, cfg, fqdn != null ? fqdn : host, port, https);
        } catch (SocketTimeoutException e) {
            return new Result(false, "Failure: The endpoint did not respond within "
                    + RESPONSE_TIMEOUT_MS / 1000 + " seconds.");
        } catch (IOException e) {
            return new Result(false, "Failure: " + describe(e));
        }
    }

    private Socket startTls(Socket socket, String host, int port, String fqdn, boolean sni) throws IOException {
        SSLSocket ssl = (SSLSocket) tls.createSocket(socket, host, port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setServerNames(sni && fqdn != null ? List.of(new SNIHostName(fqdn)) : List.of());
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }

    private static Result exchange(Socket socket, String healthCheckId, HealthCheckConfig cfg, String hostHeader,
                                   int port, boolean https) throws IOException {
        String path = cfg.getResourcePath() == null || cfg.getResourcePath().isBlank() ? "/" : cfg.getResourcePath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        boolean defaultPort = port == (https ? 443 : 80);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + (defaultPort ? "" : ":" + port) + "\r\n"
                + "User-Agent: Amazon-Route53-Health-Check-Service (ref " + healthCheckId
                + "; report http://amzn.to/1vsZADi)\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = socket.getInputStream();
        String head = readHead(in);
        String[] lines = head.split("\r\n");
        String[] statusLine = lines[0].split(" ", 3);
        if (statusLine.length < 2 || !statusLine[0].startsWith("HTTP/")) {
            return new Result(false, "Failure: The endpoint did not answer with an HTTP response.");
        }
        int code;
        try {
            code = Integer.parseInt(statusLine[1]);
        } catch (NumberFormatException e) {
            return new Result(false, "Failure: The endpoint did not answer with an HTTP response.");
        }
        String reason = statusLine.length > 2 ? statusLine[2] : "";
        String observed = "HTTP Status Code " + code + ", " + reason;
        if (code < 200 || code >= 400) {
            return new Result(false, "Failure: " + observed);
        }
        if (cfg.getType().endsWith("_STR_MATCH")) {
            boolean chunked = false;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].toLowerCase(Locale.ROOT);
                if (line.startsWith("transfer-encoding:") && line.contains("chunked")) {
                    chunked = true;
                }
            }
            String body = new String(chunked ? readChunked(in) : readUpTo(in, SEARCH_WINDOW_BYTES),
                    StandardCharsets.UTF_8);
            String search = cfg.getSearchString() == null ? "" : cfg.getSearchString();
            if (!body.contains(search)) {
                return new Result(false, "Failure: " + observed
                        + ". The response body did not contain the search string.");
            }
        }
        return new Result(true, "Success: " + observed);
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == HEADER_END) {
                break;
            }
            if (head.size() > MAX_HEADER_BYTES) {
                throw new IOException("The response headers exceeded " + MAX_HEADER_BYTES + " bytes.");
            }
        }
        if (head.size() == 0) {
            throw new IOException("The endpoint closed the connection without a response.");
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readUpTo(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while (body.size() < limit && (n = in.read(buffer, 0, Math.min(buffer.length, limit - body.size()))) != -1) {
            body.write(buffer, 0, n);
        }
        return body.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (body.size() < SEARCH_WINDOW_BYTES) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            if (hex.isEmpty()) {
                continue;
            }
            int size = Integer.parseInt(hex, 16);
            if (size == 0) {
                break;
            }
            body.write(readUpTo(in, Math.min(size, SEARCH_WINDOW_BYTES - body.size())));
            if (body.size() >= SEARCH_WINDOW_BYTES) {
                break;
            }
            readLine(in);
        }
        return body.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return b == -1 && line.isEmpty() ? null : line.toString();
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() + "." : message;
    }

    private static SSLSocketFactory trustingSocketFactory() {
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // Route 53's checkers do not validate the endpoint's certificate.
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // Route 53's checkers do not validate the endpoint's certificate.
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustAll}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No TLS context for Route 53 health checks", e);
        }
    }
}
