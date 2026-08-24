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
    private final ConcurrentHashMap<String, RdsAuthProxy> proxies = new ConcurrentHashMap<>();

    @Inject
    public RdsProxyManager(RdsSigV4Validator sigV4Validator) {
        this.sigV4Validator = sigV4Validator;
    }

    public void startProxy(String instanceId, DatabaseEngine engine, boolean iamEnabled,
                           int proxyPort, String backendHost, int backendPort,
                           String masterUsername, String masterPassword, String dbName,
                           RdsAuthProxy.PasswordValidator passwordValidator) {
        startProxy(instanceId, engine, iamEnabled, null, proxyPort, backendHost, backendPort,
                masterUsername, masterPassword, dbName, passwordValidator);
    }

    /**
     * Strict bind - {@code bindHost} is used exactly as given with no fallback. Use for
     * lex00/floci#124's same-port-collision loopback-alias fallback, where silently falling
     * back to the wildcard address on failure would be unsafe (see
     * {@link RdsAuthProxy#start(String, int)}).
     *
     * @param bindHost see {@link RdsAuthProxy#start(String, int)}; null/blank for the wildcard bind.
     */
    public void startProxy(String instanceId, DatabaseEngine engine, boolean iamEnabled,
                           String bindHost, int proxyPort, String backendHost, int backendPort,
                           String masterUsername, String masterPassword, String dbName,
                           RdsAuthProxy.PasswordValidator passwordValidator) {
        RdsAuthProxy proxy = new RdsAuthProxy(
                instanceId, backendHost, backendPort, engine, iamEnabled,
                masterUsername, masterPassword, dbName, sigV4Validator, passwordValidator);
        try {
            proxy.start(bindHost, proxyPort);
            proxies.put(instanceId, proxy);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start RDS proxy for instance " + instanceId
                    + " on " + (bindHost == null || bindHost.isBlank() ? "*" : bindHost)
                    + ":" + proxyPort, e);
        }
    }

    /**
     * Bind preferring {@code bindHost}, falling back to the wildcard address if that specific
     * address can't actually be bound locally. Use for the ordinary, non-colliding proxy start -
     * see {@link RdsAuthProxy#startPreferring(String, int)}.
     *
     * @param bindHost see {@link RdsAuthProxy#startPreferring(String, int)}; null/blank goes
     *                 straight to the wildcard bind.
     */
    public void startProxyPreferring(String instanceId, DatabaseEngine engine, boolean iamEnabled,
                           String bindHost, int proxyPort, String backendHost, int backendPort,
                           String masterUsername, String masterPassword, String dbName,
                           RdsAuthProxy.PasswordValidator passwordValidator) {
        RdsAuthProxy proxy = new RdsAuthProxy(
                instanceId, backendHost, backendPort, engine, iamEnabled,
                masterUsername, masterPassword, dbName, sigV4Validator, passwordValidator);
        try {
            proxy.startPreferring(bindHost, proxyPort);
            proxies.put(instanceId, proxy);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start RDS proxy for instance " + instanceId
                    + " on " + (bindHost == null || bindHost.isBlank() ? "*" : bindHost)
                    + ":" + proxyPort, e);
        }
    }

    public void stopProxy(String instanceId) {
        RdsAuthProxy proxy = proxies.remove(instanceId);
        if (proxy != null) {
            proxy.stop();
            LOG.infov("Stopped RDS proxy for instance {0}", instanceId);
        }
    }

    public void stopAll() {
        proxies.values().forEach(RdsAuthProxy::stop);
        proxies.clear();
        LOG.info("Stopped all RDS proxies");
    }
}
