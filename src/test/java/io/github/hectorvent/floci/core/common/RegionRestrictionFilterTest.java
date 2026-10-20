package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegionRestrictionFilterTest {

    private static final String AUTH_PREFIX = "AWS4-HMAC-SHA256 Credential=test/20260730/";

    @Test
    void unsetAllowListLeavesEveryRegionReachable() {
        ContainerRequestContext ctx = requestFor("ap-south-1", "ec2");

        filter(Optional.empty()).filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowedRegionPassesThrough() {
        ContainerRequestContext ctx = requestFor("us-west-2", "ec2");

        filter(Optional.of(List.of("us-east-1", "us-west-2"))).filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void regionOutsideTheAllowListIsDeniedInTheQueryShape() {
        ContainerRequestContext ctx = requestFor("ap-south-1", "ec2");
        when(ctx.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        filter(Optional.of(List.of("us-east-1", "us-west-2"))).filter(ctx);

        ArgumentCaptor<Response> denied = ArgumentCaptor.captor();
        verify(ctx).abortWith(denied.capture());
        assertEquals(403, denied.getValue().getStatus());
        String body = String.valueOf(denied.getValue().getEntity());
        assertTrue(body.contains("<Code>AccessDenied</Code>"), body);
        assertTrue(body.contains("ap-south-1"), body);
        assertTrue(body.contains("service control policy"), body);
    }

    @Test
    void regionOutsideTheAllowListIsDeniedForS3InTheS3Shape() {
        ContainerRequestContext ctx = requestFor("eu-west-1", "s3");

        filter(Optional.of(List.of("us-east-1"))).filter(ctx);

        ArgumentCaptor<Response> denied = ArgumentCaptor.captor();
        verify(ctx).abortWith(denied.capture());
        assertEquals(403, denied.getValue().getStatus());
        assertTrue(String.valueOf(denied.getValue().getEntity()).contains("<Code>AccessDenied</Code>"));
    }

    @Test
    void unsignedRequestIsNotScoped() {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getHeaderString("Authorization")).thenReturn(null);

        filter(Optional.of(List.of("us-east-1"))).filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowListEntriesAreTrimmedAndCaseInsensitive() {
        ContainerRequestContext ctx = requestFor("us-west-2", "ec2");

        filter(Optional.of(List.of(" US-WEST-2 ", ""))).filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    private RegionRestrictionFilter filter(Optional<List<String>> allowed) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.allowedRegions()).thenReturn(allowed);
        return new RegionRestrictionFilter(config, new RegionResolver("us-east-1", "000000000000"));
    }

    private ContainerRequestContext requestFor(String region, String service) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getHeaderString("Authorization"))
                .thenReturn(AUTH_PREFIX + region + "/" + service + "/aws4_request");
        return ctx;
    }
}
