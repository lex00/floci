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
 * Refuses requests signed for a region outside {@code floci.allowed-regions}, the stand-in for a
 * region-restriction service control policy.
 *
 * <p>Accounts used for evaluation are commonly locked to the regions they deploy into, and tooling
 * written against them enumerates regions and skips the ones that come back denied. Without this,
 * every region answers, so a sweep silently covers regions the account was never meant to reach and
 * the totals it reports do not mean what they mean on AWS.
 *
 * <p>Unset (the default) leaves every region reachable, so this is inert unless configured.
 */
@Provider
@Priority(Priorities.AUTHORIZATION - 10)
@ApplicationScoped
public class RegionRestrictionFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RegionRestrictionFilter.class);

    /** Extracts the credential-scope service name, for shaping the error like that protocol does. */
    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    private final EmulatorConfig config;
    private final RegionResolver regionResolver;

    @Inject
    public RegionRestrictionFilter(EmulatorConfig config, RegionResolver regionResolver) {
        this.config = config;
        this.regionResolver = regionResolver;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        Set<String> allowed = allowedRegions();
        if (allowed.isEmpty()) {
            return;
        }
        String auth = ctx.getHeaderString("Authorization");
        if (auth == null) {
            return; // unsigned request — nothing to scope
        }
        String region = regionResolver.resolveRegionFromAuth(auth);
        if (region == null || allowed.contains(region.toLowerCase(Locale.ROOT))) {
            return;
        }
        LOG.debugv("Denying request signed for region {0}; allowed: {1}", region, allowed);
        String service = credentialScope(auth);
        String message = "User is not authorized to perform: " + service + " operations in region "
                + region + " with an explicit deny in a service control policy";
        ctx.abortWith(IamEnforcementFilter.deniedResponse(message, service, ctx.getMediaType()));
    }

    private Set<String> allowedRegions() {
        List<String> configured = config.allowedRegions().orElse(List.of());
        return configured.stream()
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(r -> r.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String credentialScope(String auth) {
        Matcher matcher = SERVICE_PATTERN.matcher(auth);
        return matcher.find() ? matcher.group(1) : "aws";
    }
}
