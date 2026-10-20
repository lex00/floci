package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.JwtSignatureVerifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentRuntimeInboundAuthTest {

    private static final String ISSUER = "https://issuer.example/pool";
    private static final String SIGV4 = "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/bedrock-agentcore/aws4_request";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JwtSignatureVerifier verifier = mock(JwtSignatureVerifier.class);
    private final AgentRuntimeInboundAuth auth = new AgentRuntimeInboundAuth(verifier, mapper);

    private JsonNode authorizer(String extra) throws Exception {
        return mapper.readTree("{\"customJWTAuthorizer\":{\"discoveryUrl\":\"" + ISSUER
                + "/.well-known/openid-configuration\"" + extra + "}}");
    }

    private static String token(String claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private static String claims(String extra) {
        return "{\"iss\":\"" + ISSUER + "\",\"exp\":" + (Instant.now().getEpochSecond() + 300) + extra + "}";
    }

    @Test
    void theAuthorizationMethodMustMatchTheRuntime() throws Exception {
        assertDoesNotThrow(() -> auth.authorize(null, SIGV4));
        assertEquals(AgentRuntimeInboundAuth.MISMATCH, assertThrows(AwsException.class,
                () -> auth.authorize(null, token(claims("")))).getMessage());
        AwsException sigv4OnJwt = assertThrows(AwsException.class, () -> auth.authorize(authorizer(""), SIGV4));
        assertEquals("AccessDeniedException", sigv4OnJwt.getErrorCode());
        assertEquals(403, sigv4OnJwt.getHttpStatus());
    }

    @Test
    void aValidTokenPassesTheAllowedClaims() throws Exception {
        String bearer = token(claims(",\"client_id\":\"app\",\"scope\":\"agent/invoke openid\","
                + "\"department\":\"eng\""));
        auth.authorize(authorizer(",\"allowedClients\":[\"app\"],\"allowedScopes\":[\"agent/invoke\"],"
                + "\"customClaims\":[{\"inboundTokenClaimName\":\"department\",\"inboundTokenClaimValueType\":\"STRING\","
                + "\"authorizingClaimMatchValue\":{\"claimMatchValue\":{\"matchValueString\":\"eng\"},"
                + "\"claimMatchOperator\":\"EQUALS\"}}]"), bearer);

        verify(verifier).verify(any(), eq(ISSUER));
    }

    @Test
    void aTokenOutsideTheAllowedValuesIsDenied() throws Exception {
        JsonNode clients = authorizer(",\"allowedClients\":[\"app\"]");
        assertThrows(AwsException.class, () -> auth.authorize(clients, token(claims(",\"client_id\":\"other\""))));
        JsonNode audience = authorizer(",\"allowedAudience\":[\"api\"]");
        assertThrows(AwsException.class, () -> auth.authorize(audience, token(claims(",\"aud\":\"web\""))));
        assertThrows(AwsException.class, () -> auth.authorize(authorizer(""),
                token("{\"iss\":\"" + ISSUER + "\",\"exp\":1}")));
    }

    @Test
    void aTokenThatDoesNotVerifyIsDenied() throws Exception {
        doThrow(new JwtSignatureVerifier.JwtVerificationException("bad signature")).when(verifier).verify(any(), any());

        AwsException denied = assertThrows(AwsException.class,
                () -> auth.authorize(authorizer(""), token(claims(""))));
        assertEquals("OAuth authorization failed: bad signature", denied.getMessage());
    }
}
