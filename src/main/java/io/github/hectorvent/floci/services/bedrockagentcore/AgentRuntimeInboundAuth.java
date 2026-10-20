package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.JwtSignatureVerifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Inbound authorization of {@code InvokeAgentRuntime}. A runtime with a {@code customJWTAuthorizer}
 * takes a bearer token instead of a SigV4 signature, and the token must verify against the
 * discovery URL's keys and pass the audience, client, scope and custom claim checks the authorizer
 * names. A runtime without one takes SigV4 only, as on AWS.
 */
@ApplicationScoped
public class AgentRuntimeInboundAuth {

    static final String MISMATCH = "Authorization method mismatch. The agent is configured for a different "
            + "authorization method than what was used in your request.";
    private static final String DISCOVERY_SUFFIX = "/.well-known/openid-configuration";

    private final JwtSignatureVerifier verifier;
    private final ObjectMapper mapper;

    @Inject
    public AgentRuntimeInboundAuth(JwtSignatureVerifier verifier, ObjectMapper mapper) {
        this.verifier = verifier;
        this.mapper = mapper;
    }

    /** Refuses the call unless its credentials match the authorizer configuration. */
    public void authorize(JsonNode authorizerConfiguration, String authorization) {
        JsonNode jwt = authorizerConfiguration == null ? null : authorizerConfiguration.path("customJWTAuthorizer");
        boolean wantsJwt = jwt != null && jwt.isObject();
        boolean bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        if (wantsJwt != bearer) {
            throw denied(MISMATCH);
        }
        if (!wantsJwt) {
            return;
        }
        String token = authorization.substring(7).trim();
        String discoveryUrl = jwt.path("discoveryUrl").asText();
        String issuer = discoveryUrl.endsWith(DISCOVERY_SUFFIX)
                ? discoveryUrl.substring(0, discoveryUrl.length() - DISCOVERY_SUFFIX.length()) : discoveryUrl;
        try {
            verifier.verify(token, issuer);
        } catch (JwtSignatureVerifier.JwtVerificationException e) {
            throw denied("OAuth authorization failed: " + e.getMessage());
        }
        checkClaims(jwt, claims(token), issuer);
    }

    private void checkClaims(JsonNode jwt, JsonNode claims, String issuer) {
        if (claims.hasNonNull("iss") && !issuer.equals(claims.get("iss").asText())) {
            throw denied("OAuth authorization failed: the token's issuer is not " + issuer);
        }
        if (!claims.hasNonNull("exp") || claims.get("exp").asLong() <= Instant.now().getEpochSecond()) {
            throw denied("OAuth authorization failed: the token has expired");
        }
        List<String> audiences = values(claims.get("aud"));
        if (!overlaps(jwt.path("allowedAudience"), audiences)) {
            throw denied("OAuth authorization failed: the token's audience is not allowed");
        }
        List<String> clients = new ArrayList<>(values(claims.get("client_id")));
        clients.addAll(values(claims.get("azp")));
        if (clients.isEmpty()) {
            clients.addAll(audiences);
        }
        if (!overlaps(jwt.path("allowedClients"), clients)) {
            throw denied("OAuth authorization failed: the token's client is not allowed");
        }
        List<String> scopes = claims.hasNonNull("scope")
                ? List.of(claims.get("scope").asText().split(" ")) : values(claims.get("scp"));
        if (!overlaps(jwt.path("allowedScopes"), scopes)) {
            throw denied("OAuth authorization failed: the token has none of the allowed scopes");
        }
        for (JsonNode claim : jwt.path("customClaims")) {
            if (!customClaimMatches(claim, claims.get(claim.path("inboundTokenClaimName").asText()))) {
                throw denied("OAuth authorization failed: claim " + claim.path("inboundTokenClaimName").asText()
                        + " does not match");
            }
        }
    }

    /**
     * A custom claim check: {@code EQUALS} compares a string claim, {@code CONTAINS} looks for one value in
     * an array claim, and {@code CONTAINS_ANY} for any of several.
     */
    private static boolean customClaimMatches(JsonNode rule, JsonNode claim) {
        if (claim == null || claim.isNull()) {
            return false;
        }
        JsonNode match = rule.path("authorizingClaimMatchValue");
        String operator = match.path("claimMatchOperator").asText("EQUALS");
        JsonNode expected = match.path("claimMatchValue");
        List<String> actual = values(claim);
        return switch (operator) {
            case "CONTAINS" -> actual.contains(expected.path("matchValueString").asText());
            case "CONTAINS_ANY" -> values(expected.path("matchValueStringList")).stream().anyMatch(actual::contains);
            default -> claim.isTextual() && claim.asText().equals(expected.path("matchValueString").asText());
        };
    }

    /** An empty or absent allowed list allows anything; otherwise one value must be on it. */
    private static boolean overlaps(JsonNode allowed, List<String> actual) {
        List<String> permitted = values(allowed);
        return permitted.isEmpty() || actual.stream().anyMatch(permitted::contains);
    }

    private static List<String> values(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(value -> values.add(value.asText()));
        } else {
            values.add(node.asText());
        }
        return values;
    }

    private JsonNode claims(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw denied("OAuth authorization failed: the bearer token is not a JWT");
        }
        try {
            return mapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            throw denied("OAuth authorization failed: the token's claims do not parse");
        }
    }

    private static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }
}
