package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Refuses a request signed for a region outside {@code floci.allowed-regions}, as a
 * region-restriction service control policy does. Leaving it unset keeps every region reachable.
 */
@Provider
@Priority(Priorities.AUTHORIZATION - 10)
@ApplicationScoped
public class RegionRestrictionFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RegionRestrictionFilter.class);

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    private final Set<String> allowedRegions;
    private final RegionResolver regionResolver;

    @Inject
    public RegionRestrictionFilter(EmulatorConfig config, RegionResolver regionResolver) {
        this.allowedRegions = normalize(config.allowedRegions().orElse(List.of()));
        this.regionResolver = regionResolver;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (allowedRegions.isEmpty()) {
            return;
        }
        String auth = ctx.getHeaderString("Authorization");
        String region = regionResolver.resolveRegionFromAuthOrNull(auth);
        if (region == null || allowedRegions.contains(region.toLowerCase(Locale.ROOT))) {
            return;
        }
        LOG.debugv("Denying request signed for region {0}; allowed: {1}", region, allowedRegions);
        String service = credentialScope(auth);
        String message = "User is not authorized to perform: " + service + " operations in region "
                + region + " with an explicit deny in a service control policy";
        ctx.abortWith(IamEnforcementFilter.deniedForRequest(message, service, ctx));
    }

    private static Set<String> normalize(List<String> configured) {
        return configured.stream()
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(r -> r.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String credentialScope(String auth) {
        Matcher matcher = SERVICE_PATTERN.matcher(auth);
        return matcher.find() ? matcher.group(1) : "";
    }
}
