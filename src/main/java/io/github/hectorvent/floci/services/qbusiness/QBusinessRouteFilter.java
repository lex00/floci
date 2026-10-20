package io.github.hectorvent.floci.services.qbusiness;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Rewrites Q Business application requests to an internal prefix before JAX-RS route matching,
 * since AppConfig and EMR Serverless also serve {@code /applications}.
 */
@Provider
@PreMatching
public class QBusinessRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_qbusiness";

    private final SignedServiceRoute route;

    @Inject
    public QBusinessRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "qbusiness", INTERNAL_PREFIX,
                SignedServiceRoute.under("/applications"));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
