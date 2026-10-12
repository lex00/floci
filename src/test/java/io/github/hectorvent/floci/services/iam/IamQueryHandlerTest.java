package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Direct Query handler tests with a mocked service, without a Quarkus application or HTTP server. */
class IamQueryHandlerTest {

    private static final String ROLE_NAME = "TimestampRole";
    private static final String CERTIFICATE_NAME = "TimestampCertificate";

    /**
     * Smithy's date-time format carries at most millisecond precision and asks serializers to truncate
     * anything finer; ISO_INSTANT then prints the fraction in groups of three, or not at all.
     */
    static Stream<Arguments> timestampCases() {
        return Stream.of(
                Arguments.of("2026-10-05T05:47:29.777826302Z", "2026-10-05T05:47:29.777Z"),
                Arguments.of("2026-10-05T05:47:29.785090420Z", "2026-10-05T05:47:29.785Z"),
                Arguments.of("2026-10-05T05:47:29Z", "2026-10-05T05:47:29Z"),
                Arguments.of("2026-10-05T05:47:29.000000001Z", "2026-10-05T05:47:29Z"),
                Arguments.of("2026-10-05T05:47:29.000999999Z", "2026-10-05T05:47:29Z"),
                Arguments.of("2026-10-05T05:47:29.001000000Z", "2026-10-05T05:47:29.001Z"),
                Arguments.of("2026-10-05T05:47:29.010000000Z", "2026-10-05T05:47:29.010Z"),
                Arguments.of("2026-12-31T23:59:59.999999999Z", "2026-12-31T23:59:59.999Z"),
                Arguments.of("1969-12-31T23:59:59.999999999Z", "1969-12-31T23:59:59.999Z"));
    }

    @ParameterizedTest
    @MethodSource("timestampCases")
    void serverCertificateResponsesWriteMillisecondTimestamps(String stored, String expected) throws Exception {
        Instant timestamp = Instant.parse(stored);
        ServerCertificate certificate = new ServerCertificate();
        certificate.setServerCertificateName(CERTIFICATE_NAME);
        certificate.setServerCertificateId("certificate-id");
        certificate.setArn("arn:aws:iam::000000000000:server-certificate/TimestampCertificate");
        certificate.setCertificateBody("fabricated-certificate");
        certificate.setUploadDate(timestamp);
        certificate.setExpiration(timestamp);

        IamService service = mock(IamService.class);
        when(service.uploadServerCertificate(CERTIFICATE_NAME, "/", "fabricated-certificate",
                "fabricated-key", null, Map.of())).thenReturn(certificate);
        when(service.getServerCertificate(CERTIFICATE_NAME)).thenReturn(certificate);
        when(service.listServerCertificates(null)).thenReturn(List.of(certificate));
        IamQueryHandler handler = new IamQueryHandler(service, null, null, null, null, null, null, null);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ServerCertificateName", CERTIFICATE_NAME);
        params.putSingle("Path", "/");
        params.putSingle("CertificateBody", "fabricated-certificate");
        params.putSingle("PrivateKey", "fabricated-key");

        for (String action : List.of("UploadServerCertificate", "GetServerCertificate", "ListServerCertificates")) {
            try (Response response = handler.handle(action, params, null)) {
                assertXmlMember(response, "UploadDate", expected);
                assertXmlMember(response, "Expiration", expected);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("timestampCases")
    void roleResponsesWriteMillisecondTimestamps(String stored, String expected) throws Exception {
        IamRole role = new IamRole();
        role.setRoleName(ROLE_NAME);
        role.setRoleId("role-id");
        role.setPath("/");
        role.setArn("arn:aws:iam::000000000000:role/TimestampRole");
        role.setAssumeRolePolicyDocument("{}");
        role.setCreateDate(Instant.parse(stored));
        IamService service = mock(IamService.class);
        when(service.createRole(ROLE_NAME, "/", "{}", null, 3600, Map.of(), null)).thenReturn(role);
        when(service.getValidatedRole(ROLE_NAME)).thenReturn(role);
        IamQueryHandler handler = new IamQueryHandler(service, null, null, null, null, null, null, null);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleName", ROLE_NAME);
        params.putSingle("Path", "/");
        params.putSingle("AssumeRolePolicyDocument", "{}");

        for (String action : List.of("CreateRole", "GetRole")) {
            try (Response response = handler.handle(action, params, null)) {
                assertXmlMember(response, "CreateDate", expected);
            }
        }
    }

    /** The page is cut in the order the marker is compared in, whatever order the service hands back. */
    @Test
    void listRolesPagesInNameOrderWhateverOrderTheServiceReturns() throws Exception {
        IamService service = mock(IamService.class);
        when(service.listRoles(null)).thenReturn(List.of(role("c"), role("a"), role("b")));
        IamQueryHandler handler = new IamQueryHandler(service, null, null, null, null, null, null, null);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("MaxItems", "2");
        Document first = list(handler, "ListRoles", params);
        assertEquals(List.of("a", "b"), texts(first, "RoleName"));
        assertEquals("true", texts(first, "IsTruncated").get(0));

        params.putSingle("Marker", texts(first, "Marker").get(0));
        Document second = list(handler, "ListRoles", params);
        assertEquals(List.of("c"), texts(second, "RoleName"));
        assertTrue(texts(second, "Marker").isEmpty());
    }

    /**
     * An account may name its own policy after an AWS managed one, so a cursor on the name alone
     * would skip the second of the two. Customer managed policies come first, as before.
     */
    @Test
    void listPoliciesKeepsACustomerPolicyAndAnAwsManagedOneOfTheSameNameApart() throws Exception {
        IamPolicy local = policy("arn:aws:iam::000000000000:policy/ReadOnlyAccess");
        IamPolicy managed = policy("arn:aws:iam::aws:policy/ReadOnlyAccess");
        IamService service = mock(IamService.class);
        when(service.listPolicies(null, null)).thenReturn(List.of(managed, local));
        IamQueryHandler handler = new IamQueryHandler(service, null, null, null, null, null, null, null);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("MaxItems", "1");
        List<String> arns = new ArrayList<>();
        String marker;
        do {
            Document page = list(handler, "ListPolicies", params);
            arns.addAll(texts(page, "Arn"));
            List<String> markers = texts(page, "Marker");
            marker = markers.isEmpty() ? null : markers.get(0);
            params.putSingle("Marker", marker);
        } while (marker != null);

        assertEquals(List.of(local.getArn(), managed.getArn()), arns);
    }

    @Test
    void encodedMarkerIsUrlSafeAndUnpadded() {
        String marker = IamQueryHandler.encodeMarker("role-name");
        assertFalse(marker.contains("="));
        assertTrue(marker.matches("[A-Za-z0-9_-]+"), marker);
    }

    private static IamRole role(String name) {
        IamRole role = new IamRole();
        role.setRoleName(name);
        role.setRoleId("role-id-" + name);
        role.setPath("/");
        role.setArn("arn:aws:iam::000000000000:role/" + name);
        role.setAssumeRolePolicyDocument("{}");
        role.setCreateDate(Instant.EPOCH);
        return role;
    }

    private static IamPolicy policy(String arn) {
        IamPolicy policy = new IamPolicy();
        policy.setPolicyName(arn.substring(arn.lastIndexOf('/') + 1));
        policy.setPolicyId("policy-id-" + arn.hashCode());
        policy.setPath("/");
        policy.setArn(arn);
        policy.setDefaultVersionId("v1");
        policy.setCreateDate(Instant.EPOCH);
        policy.setUpdateDate(Instant.EPOCH);
        return policy;
    }

    private static Document list(IamQueryHandler handler, String action,
                                 MultivaluedMap<String, String> params) throws Exception {
        try (Response response = handler.handle(action, params, null)) {
            assertEquals(200, response.getStatus());
            return XmlParser.parseDocument((String) response.getEntity());
        }
    }

    private static List<String> texts(Document document, String member) {
        NodeList matches = document.getElementsByTagNameNS("*", member);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < matches.getLength(); i++) {
            values.add(matches.item(i).getTextContent());
        }
        return values;
    }

    private static void assertXmlMember(Response response, String member, String expected) throws Exception {
        assertEquals(200, response.getStatus());
        Document document = XmlParser.parseDocument((String) response.getEntity());
        NodeList matches = document.getElementsByTagNameNS("*", member);
        assertEquals(1, matches.getLength(), member);
        assertEquals(expected, matches.item(0).getTextContent(), member);
    }
}
