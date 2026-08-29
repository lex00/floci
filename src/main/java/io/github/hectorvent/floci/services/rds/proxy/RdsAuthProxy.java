package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP auth proxy for a single RDS DB instance or cluster.
 * Dispatches to the appropriate engine-specific protocol handler for the
 * auth intercept, then bridges client ↔ backend transparently.
 */
public class RdsAuthProxy {

    private static final Logger LOG = Logger.getLogger(RdsAuthProxy.class);

    private final int backendPort;
    private final boolean iamEnabled;
    private final String instanceId;
    private final String backendHost;
    private final String masterUsername;
    private final String masterPassword;
    private final String dbName;
    private final DatabaseEngine engine;
    private final RdsSigV4Validator sigV4;
    private final RdsProxyTlsCertificates tlsCertificates;
    private final PasswordValidator passwordValidator;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public RdsAuthProxy(String instanceId, String backendHost, int backendPort,
                        DatabaseEngine engine, boolean iamEnabled,
                        String masterUsername, String masterPassword, String dbName,
                        RdsSigV4Validator sigV4, RdsProxyTlsCertificates tlsCertificates,
                        PasswordValidator passwordValidator) {
        this.instanceId = instanceId;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.engine = engine;
        this.iamEnabled = iamEnabled;
        this.masterUsername = masterUsername;
        this.masterPassword = masterPassword;
        this.dbName = dbName;
        this.sigV4 = sigV4;
        this.tlsCertificates = tlsCertificates;
        this.passwordValidator = passwordValidator;
    }

    public void start(int proxyPort) throws IOException {
        start(null, proxyPort, false);
    }

    /**
     * Strict bind: {@code bindHost} (or the wildcard address, if null/blank) is used exactly as
     * given, with no fallback. Used for lex00/floci#124's same-port-collision fallback - a
     * distinct loopback alias (127.0.0.x) chosen specifically so a second instance can bind its
     * own literal copy of a port another instance already holds. Falling back to the wildcard
     * address here on failure would be unsafe: the wildcard is exactly the address the
     * COLLIDING instance already occupies that port on, so silently reusing it would recreate
     * the wrong-instance mix-up f1b2520c reverted round 5's fix over. Callers should catch a
     * failure here and fall back to a different PORT instead (the pre-#124 safe behavior).
     */
    public void start(String bindHost, int proxyPort) throws IOException {
        start(bindHost, proxyPort, false);
    }

    /**
     * Bind with a same-process wildcard fallback: {@code bindHost} is tried first when
     * non-null/non-blank, and if it can't actually be bound locally (e.g. it's a DNS alias like
     * {@code host.docker.internal} that doesn't correspond to one of this process's own
     * interfaces - the {@code dockerHostResolver} native-host fallback case), this falls back to
     * the wildcard address exactly like today's original single-arg {@code start(int)} always
     * did.
     *
     * <p>lex00/floci#124: a wildcard bind occupies EVERY address on that port, not just the
     * wildcard's own - so a second instance can never bind the identical port on its own
     * distinct loopback alias while even an unrelated *default* instance still holds that port
     * on the wildcard. Callers for the ordinary, non-colliding case use this method with their
     * resolved default address as a concrete candidate instead of the strict {@link
     * #start(String, int)}, so the common case stops occupying the wildcard wherever a real
     * local bind target is known, while still degrading exactly to today's behavior wherever
     * one isn't.
     */
    public void startPreferring(String bindHost, int proxyPort) throws IOException {
        start(bindHost, proxyPort, true);
    }

    private void start(String bindHost, int proxyPort, boolean fallbackToWildcardOnFailure) throws IOException {
        if (bindHost != null && !bindHost.isBlank()) {
            try {
                serverSocket = new ServerSocket(proxyPort, 50, InetAddress.getByName(bindHost));
                running = true;
                Thread.ofVirtual().name("rds-proxy-accept-" + instanceId).start(this::acceptLoop);
                LOG.infov("RDS proxy started for instance {0} on {1}:{2} → {3}:{4}",
                        instanceId, bindHost, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
                return;
            } catch (IOException e) {
                if (!fallbackToWildcardOnFailure) {
                    throw e;
                }
                LOG.debugv("RDS proxy for instance {0} could not bind {1}:{2} ({3}); "
                        + "falling back to the wildcard address", instanceId, bindHost,
                        String.valueOf(proxyPort), e.getMessage());
            }
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(proxyPort));
        running = true;
        Thread.ofVirtual().name("rds-proxy-accept-" + instanceId).start(this::acceptLoop);
        LOG.infov("RDS proxy started for instance {0} on *:{1} → {2}:{3}",
                instanceId, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warnv(e, "Error closing RDS proxy server socket for instance {0}", instanceId);
            throw new RuntimeException(
                    "Failed to stop RDS proxy for instance " + instanceId, e);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("rds-proxy-conn-" + instanceId)
                        .start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running) {
                    LOG.warnv("Accept error for RDS instance {0}: {1}", instanceId, e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket client) {
        try {
            client.setTcpNoDelay(true);
            Socket backend = new Socket(backendHost, backendPort);
            backend.setTcpNoDelay(true);

            switch (engine) {
                case POSTGRES -> PostgresProtocolHandler.handleAuth(
                        client, backend, masterUsername, masterPassword, dbName,
                        iamEnabled, sigV4, tlsCertificates, passwordValidator::validate);
                case MYSQL, MARIADB -> MySqlProtocolHandler.handleAuth(
                        client, backend, masterUsername, masterPassword,
                        iamEnabled, sigV4, tlsCertificates, passwordValidator::validate);
            }
        } catch (Exception e) {
            LOG.debugv("RDS connection error for instance {0}: {1}", instanceId, e.getMessage());
            closeQuietly(client);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            LOG.debugv(e, "Error closing RDS proxy client socket");
        }
    }

    /**
     * Callback for password validation — implemented by RdsService.
     */
    @FunctionalInterface
    public interface PasswordValidator {
        boolean validate(String username, String password);
    }
}
