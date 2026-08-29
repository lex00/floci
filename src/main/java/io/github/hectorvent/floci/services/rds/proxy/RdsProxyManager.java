package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all active RDS auth proxies. One proxy per DB instance or cluster.
 */
@ApplicationScoped
public class RdsProxyManager {

    private static final Logger LOG = Logger.getLogger(RdsProxyManager.class);

    private final RdsSigV4Validator sigV4Validator;
    private final RdsProxyTlsCertificates tlsCertificates;
    private final ConcurrentHashMap<String, RdsAuthProxy> proxies = new ConcurrentHashMap<>();

    @Inject
    public RdsProxyManager(RdsSigV4Validator sigV4Validator, RdsProxyTlsCertificates tlsCertificates) {
        this.sigV4Validator = sigV4Validator;
        this.tlsCertificates = tlsCertificates;
    }

    public synchronized void startProxy(String instanceId, DatabaseEngine engine, boolean iamEnabled,
                                        int proxyPort, String backendHost, int backendPort,
                                        String advertisedHost,
                                        String masterUsername, String masterPassword, String dbName,
                                        RdsAuthProxy.PasswordValidator passwordValidator) {
        startInternal(instanceId, engine, iamEnabled, null, false, proxyPort, backendHost, backendPort,
                advertisedHost, masterUsername, masterPassword, dbName, passwordValidator);
    }

    /**
     * Strict bind - {@code bindHost} is used exactly as given with no fallback. Use for
     * lex00/floci#124's same-port-collision loopback-alias fallback, where silently falling
     * back to the wildcard address on failure would be unsafe (see
     * {@link RdsAuthProxy#start(String, int)}).
     *
     * @param bindHost see {@link RdsAuthProxy#start(String, int)}; null/blank for the wildcard bind.
     */
    public synchronized void startProxy(String instanceId, DatabaseEngine engine, boolean iamEnabled,
                                        String bindHost, int proxyPort, String backendHost, int backendPort,
                                        String advertisedHost,
                                        String masterUsername, String masterPassword, String dbName,
                                        RdsAuthProxy.PasswordValidator passwordValidator) {
        startInternal(instanceId, engine, iamEnabled, bindHost, false, proxyPort, backendHost, backendPort,
                advertisedHost, masterUsername, masterPassword, dbName, passwordValidator);
    }

    /**
     * Bind preferring {@code bindHost}, falling back to the wildcard address if that specific
     * address can't actually be bound locally. Use for the ordinary, non-colliding proxy start -
     * see {@link RdsAuthProxy#startPreferring(String, int)}.
     *
     * @param bindHost see {@link RdsAuthProxy#startPreferring(String, int)}; null/blank goes
     *                 straight to the wildcard bind.
     */
    public synchronized void startProxyPreferring(String instanceId, DatabaseEngine engine, boolean iamEnabled,
                                        String bindHost, int proxyPort, String backendHost, int backendPort,
                                        String advertisedHost,
                                        String masterUsername, String masterPassword, String dbName,
                                        RdsAuthProxy.PasswordValidator passwordValidator) {
        startInternal(instanceId, engine, iamEnabled, bindHost, true, proxyPort, backendHost, backendPort,
                advertisedHost, masterUsername, masterPassword, dbName, passwordValidator);
    }

    private void startInternal(String instanceId, DatabaseEngine engine, boolean iamEnabled,
                               String bindHost, boolean fallbackToWildcard,
                               int proxyPort, String backendHost, int backendPort,
                               String advertisedHost,
                               String masterUsername, String masterPassword, String dbName,
                               RdsAuthProxy.PasswordValidator passwordValidator) {
        tlsCertificates.ensureHost(advertisedHost);
        RdsAuthProxy proxy = new RdsAuthProxy(
                instanceId, backendHost, backendPort, engine, iamEnabled,
                masterUsername, masterPassword, dbName, sigV4Validator, tlsCertificates, passwordValidator);
        try {
            if (bindHost == null || bindHost.isBlank()) {
                proxy.start(proxyPort);
            } else if (fallbackToWildcard) {
                proxy.startPreferring(bindHost, proxyPort);
            } else {
                proxy.start(bindHost, proxyPort);
            }
        } catch (IOException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to start RDS proxy for instance " + instanceId
                            + " on " + (bindHost == null || bindHost.isBlank() ? "*" : bindHost)
                            + ":" + proxyPort, e);
            cleanupFailedStart(proxy, failure);
            throw failure;
        } catch (RuntimeException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to register RDS proxy for instance " + instanceId
                            + " on port " + proxyPort, e);
            cleanupFailedStart(proxy, failure);
            throw failure;
        }
        RdsAuthProxy previous;
        try {
            previous = proxies.put(instanceId, proxy);
        } catch (RuntimeException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to register RDS proxy for instance " + instanceId
                            + " on port " + proxyPort, e);
            cleanupFailedStart(proxy, failure);
            throw failure;
        }
        if (previous != null) {
            try {
                previous.stop();
            } catch (RuntimeException e) {
                proxies.put(instanceId, previous);
                RuntimeException failure = new RuntimeException(
                        "Failed to replace RDS proxy for instance " + instanceId, e);
                cleanupFailedStart(proxy, failure);
                throw failure;
            }
        }
    }

    public synchronized void stopProxy(String instanceId) {
        RdsAuthProxy proxy = proxies.get(instanceId);
        if (proxy != null) {
            proxy.stop();
            proxies.remove(instanceId, proxy);
            LOG.infov("Stopped RDS proxy for instance {0}", instanceId);
        }
    }

    public synchronized void stopAll() {
        proxies.forEach((instanceId, proxy) -> {
            try {
                proxy.stop();
                proxies.remove(instanceId, proxy);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Failed to stop RDS proxy for instance {0} during shutdown",
                        instanceId);
            }
        });
        LOG.info("Stopped all RDS proxies");
    }

    private void cleanupFailedStart(RdsAuthProxy proxy, RuntimeException failure) {
        try {
            proxy.stop();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
