package io.github.hectorvent.floci.services.dms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class DmsCertificateIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonDMSv20160101.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/dms/aws4_request";
    private static final String PEM =
            "-----BEGIN CERTIFICATE-----\n" +
            "MIIDEzCCAfugAwIBAgIUfZZvF0fNUuMHB4BYxK7Lq7+yZscwDQYJKoZIhvcNAQEL\n" +
            "BQAwGTEXMBUGA1UEAwwOZmxvY2ktZG1zLXRlc3QwHhcNMjYxMDIwMTg0MDM5WhcN\n" +
            "MzYxMDE3MTg0MDM5WjAZMRcwFQYDVQQDDA5mbG9jaS1kbXMtdGVzdDCCASIwDQYJ\n" +
            "KoZIhvcNAQEBBQADggEPADCCAQoCggEBAL3+dtVWjyqn2lbkaxDGVVTUNemBrGvC\n" +
            "r4Gsd0YZyB30hqEketdsNQatl/544Q1NJSGYPaiJZLTvDoLfuayBOvDlj8b8Z2JI\n" +
            "+PlONdqaaNBIOSgwc3qVWfkcKAOWqsFw9JYvrs7DY/3cVqfy6FFNUDcFyNWMAyYz\n" +
            "Fs4jqehwbhfD4O8vhLK9BA821EBlcTucnJVyaj9+buDX2n4wxAaT+3RGIs55xNEW\n" +
            "/kPfwEu5niVDSW0u2JIpzCSg43YZwdZ3qnobtoo13QmvTPeTx/cGouJD0QCjDaeM\n" +
            "s/LfOAq4JQuZ4q3DmYfEUuLyt/Ud2yKsevIpklcBJMQlndqHqIvh6xECAwEAAaNT\n" +
            "MFEwHQYDVR0OBBYEFOClb2f1/Aq81eSxF+6aLUk8wv3aMB8GA1UdIwQYMBaAFOCl\n" +
            "b2f1/Aq81eSxF+6aLUk8wv3aMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEL\n" +
            "BQADggEBAFMjKcMxyTG8FY3lO9Lt6+SDhBkgJFwnELeGcVXy97xfeH7PA3Juzomm\n" +
            "tDi9c/HyvNjg5cFllSg+JD6VvHqpWPgk36GnRj3WntmpmvGr6QnoHhXwmblaAYU+\n" +
            "HVATUiXEA6t2XPMTFmxXJjh9sDxdVlnLvdWYk0b+oX/hKjzW9MC031RRbiW7c52G\n" +
            "pl9gKUEnxbvAhjQO2AJFe9oLkVqHmZVTlqFZcSgtnCHecJgk+1ic0YmxBXHPSmfy\n" +
            "BW1ZUqJcG0LVy3p4ZB7KPs5t9flYZzaYAEwbBAiHZfKDAlNW0Mv/N670JzaxEQD3\n" +
            "M+TjcnhGQuoxWXORQKTavJ6hoN/rb88=\n" +
            "-----END CERTIFICATE-----\n";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void importDescribeAndDeleteAPemCertificate() {
        String arn = dms("ImportCertificate")
                .body(Map.of("CertificateIdentifier", "probe-cert", "CertificatePem", PEM,
                        "Tags", List.of(Map.of("Key", "env", "Value", "test"))))
            .when().post("/")
            .then()
                .statusCode(200)
                .body("Certificate.CertificateIdentifier", equalTo("probe-cert"))
                .body("Certificate.SigningAlgorithm", equalTo("SHA256withRSA"))
                .body("Certificate.KeyLength", equalTo(2048))
                .body("Certificate.ValidToDate", notNullValue())
                .body("Certificate.CertificateArn", containsString(":dms:us-east-1:"))
                .extract().path("Certificate.CertificateArn");

        dms("DescribeCertificates")
                .body("{\"Filters\":[{\"Name\":\"certificate-id\",\"Values\":[\"probe-cert\"]}]}")
            .when().post("/")
            .then()
                .statusCode(200)
                .body("Certificates[0].CertificateArn", equalTo(arn))
                .body("Certificates[0].CertificatePem", containsString("BEGIN CERTIFICATE"));

        dms("ListTagsForResource").body(Map.of("ResourceArn", arn))
            .when().post("/")
            .then().statusCode(200).body("TagList[0].Key", equalTo("env"));

        dms("ImportCertificate").body(Map.of("CertificateIdentifier", "probe-cert", "CertificatePem", PEM))
            .when().post("/")
            .then().statusCode(400).body(containsString("ResourceAlreadyExistsFault"));

        dms("DeleteCertificate").body(Map.of("CertificateArn", arn))
            .when().post("/")
            .then().statusCode(200).body("Certificate.CertificateIdentifier", equalTo("probe-cert"));

        dms("DescribeCertificates")
                .body("{\"Filters\":[{\"Name\":\"certificate-arn\",\"Values\":[\"" + arn + "\"]}]}")
            .when().post("/")
            .then().statusCode(400).body(containsString("ResourceNotFoundFault"));
    }

    @Test
    void anInvalidPemIsRejected() {
        dms("ImportCertificate").body(Map.of("CertificateIdentifier", "bad-cert", "CertificatePem", "not a cert"))
            .when().post("/")
            .then().statusCode(400).body(containsString("InvalidCertificateFault"));
    }

    private static RequestSpecification dms(String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER);
    }
}
