package io.github.hectorvent.floci.services.cloudwatch.alarmmuterules;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudWatchAlarmMuteRulesIntegrationTest {

    private static final String CW_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/monitoring/aws4_request";
    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String ARN_PREFIX = "arn:aws:cloudwatch:us-east-1:000000000000:alarm-mute-rule:";

    @BeforeAll
    static void registerJsonParser() {
        RestAssured.registerParser(JSON_1_0, Parser.JSON);
    }

    private static RequestSpecification json(String target, String body) {
        return given()
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(JSON_1_0, ContentType.TEXT)))
                .contentType(JSON_1_0)
                .header("X-Amz-Target", "GraniteServiceVersion20100801." + target)
                .body(body);
    }

    private static RequestSpecification query(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CW_SCOPE)
                .formParam("Action", action);
    }

    @Test
    void jsonPutGetListTagAndDelete() {
        json("PutAlarmMuteRule", """
                {"Name":"nightly","Description":"maintenance",
                 "Rule":{"Schedule":{"Expression":"cron(0 0 * * ? *)","Duration":"PT1H","Timezone":"UTC"}},
                 "MuteTargets":{"AlarmNames":["cpu-high"]},
                 "Tags":[{"Key":"owner","Value":"ops"}]}
                """).when().post("/").then().statusCode(200);

        json("GetAlarmMuteRule", "{\"AlarmMuteRuleName\":\"nightly\"}")
                .when().post("/").then().statusCode(200)
                .body("Name", equalTo("nightly"))
                .body("AlarmMuteRuleArn", equalTo(ARN_PREFIX + "nightly"))
                .body("Description", equalTo("maintenance"))
                .body("Rule.Schedule.Expression", equalTo("cron(0 0 * * ? *)"))
                .body("Rule.Schedule.Duration", equalTo("PT1H"))
                .body("MuteTargets.AlarmNames", hasItem("cpu-high"))
                .body("MuteType", equalTo("RECURRING"))
                .body("Status", equalTo("ACTIVE"));

        json("ListAlarmMuteRules", "{\"AlarmName\":\"cpu-high\"}")
                .when().post("/").then().statusCode(200)
                .body("AlarmMuteRuleSummaries.AlarmMuteRuleArn", hasItem(ARN_PREFIX + "nightly"));

        json("ListTagsForResource", "{\"ResourceARN\":\"" + ARN_PREFIX + "nightly\"}")
                .when().post("/").then().statusCode(200)
                .body("Tags[0].Key", equalTo("owner"));

        json("DeleteAlarmMuteRule", "{\"AlarmMuteRuleName\":\"nightly\"}")
                .when().post("/").then().statusCode(200);
        json("GetAlarmMuteRule", "{\"AlarmMuteRuleName\":\"nightly\"}")
                .when().post("/").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        json("ListAlarmMuteRules", "{}")
                .when().post("/").then().statusCode(200)
                .body("AlarmMuteRuleSummaries.AlarmMuteRuleArn", not(hasItem(ARN_PREFIX + "nightly")));
    }

    @Test
    void jsonRejectsAnInvalidSchedule() {
        json("PutAlarmMuteRule", """
                {"Name":"bad","Rule":{"Schedule":{"Expression":"rate(1 hour)","Duration":"PT1H"}}}
                """).when().post("/").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValue"));
    }

    @Test
    void queryPutThenGetOneTimeRule() {
        query("PutAlarmMuteRule")
                .formParam("Name", "one-off")
                .formParam("Rule.Schedule.Expression", "at(2020-01-01T00:00)")
                .formParam("Rule.Schedule.Duration", "PT2H")
                .formParam("MuteTargets.AlarmNames.member.1", "disk-full")
            .when().post("/").then().statusCode(200);

        query("GetAlarmMuteRule")
                .formParam("AlarmMuteRuleName", "one-off")
            .when().post("/").then().statusCode(200)
                .body("GetAlarmMuteRuleResponse.GetAlarmMuteRuleResult.AlarmMuteRuleArn",
                        equalTo(ARN_PREFIX + "one-off"))
                .body("GetAlarmMuteRuleResponse.GetAlarmMuteRuleResult.MuteType", equalTo("ONE_TIME"))
                .body("GetAlarmMuteRuleResponse.GetAlarmMuteRuleResult.Status", equalTo("EXPIRED"))
                .body("GetAlarmMuteRuleResponse.GetAlarmMuteRuleResult.MuteTargets.AlarmNames.member",
                        equalTo("disk-full"));
    }
}
