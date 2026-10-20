package io.github.hectorvent.floci.services.cloudfront;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CloudFrontAnycastIpListIntegrationTest {

    private static final String API = "/2020-05-31/anycast-ip-list";

    private static String createBody(String name, int count) {
        return """
                <CreateAnycastIpListRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <Name>%s</Name>
                  <IpCount>%d</IpCount>
                  <Tags><Items><Tag><Key>team</Key><Value>edge</Value></Tag></Items></Tags>
                </CreateAnycastIpListRequest>
                """.formatted(name, count);
    }

    @Test
    void createGetListUpdateAndDelete() {
        ExtractableResponse<Response> created = given()
                .contentType("application/xml")
                .body(createBody("anycast-it", 3))
                .when().post(API)
                .then()
                .statusCode(202)
                .body("AnycastIpList.Name", equalTo("anycast-it"))
                .body("AnycastIpList.Status", equalTo("Deployed"))
                .body("AnycastIpList.IpCount", equalTo("3"))
                .body("AnycastIpList.IpAddressType", equalTo("ipv4"))
                .body("AnycastIpList.AnycastIps.AnycastIp.size()", equalTo(3))
                .body("AnycastIpList.Arn", startsWith("arn:aws:cloudfront::000000000000:anycast-ip-list/"))
                .extract();
        String id = created.path("AnycastIpList.Id");
        String arn = created.path("AnycastIpList.Arn");
        String etag = created.header("ETag");

        given().when().get(API + "/" + id)
                .then().statusCode(200)
                .body("AnycastIpList.Id", equalTo(id));

        given().when().get(API)
                .then().statusCode(200)
                .body("AnycastIpListCollection.Items.AnycastIpListSummary.Id.list()", hasItem(id));

        given().queryParam("Resource", arn).when().get("/2020-05-31/tagging")
                .then().statusCode(200)
                .body("Tags.Items.Tag.Key", equalTo("team"));

        String updatedEtag = given()
                .contentType("application/xml")
                .header("If-Match", etag)
                .body("<UpdateAnycastIpListRequest xmlns=\"http://cloudfront.amazonaws.com/doc/2020-05-31/\">"
                        + "<IpAddressType>dualstack</IpAddressType></UpdateAnycastIpListRequest>")
                .when().put(API + "/" + id)
                .then().statusCode(200)
                .body("AnycastIpList.IpAddressType", equalTo("dualstack"))
                .extract().header("ETag");

        given().header("If-Match", etag).when().delete(API + "/" + id)
                .then().statusCode(412);
        given().header("If-Match", updatedEtag).when().delete(API + "/" + id)
                .then().statusCode(204);
        given().when().get(API + "/" + id)
                .then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("EntityNotFound"));
        given().when().get(API)
                .then().statusCode(200)
                .body("AnycastIpListCollection.Items.AnycastIpListSummary.Id.list()", not(hasItem(id)));
    }

    @Test
    void createRejectsAnUnsupportedIpCount() {
        given().contentType("application/xml")
                .body(createBody("anycast-bad", 5))
                .when().post(API)
                .then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidArgument"));
    }
}
