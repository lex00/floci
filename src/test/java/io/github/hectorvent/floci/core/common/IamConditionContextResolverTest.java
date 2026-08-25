package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamConditionContextResolverTest {

    private Ec2Service ec2Service;
    private S3Service s3Service;
    private EmulatorConfig config;
    private RequestContext requestContext;
    private IamConditionContextResolver resolver;

    @BeforeEach
    void setUp() {
        ec2Service = mock(Ec2Service.class);
        s3Service = mock(S3Service.class);
        config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");
        requestContext = new RequestContext();
        requestContext.setRegion("us-east-1");
        resolver = new IamConditionContextResolver(config, requestContext, ec2Service, s3Service);
    }

    private ContainerRequestContext formRequest(String path, String body) {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(containerRequest.getMediaType())
                .thenReturn(MediaType.valueOf("application/x-www-form-urlencoded"));
        when(containerRequest.getEntityStream())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return containerRequest;
    }

    private ContainerRequestContext xmlRequest(String path, String body) {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        when(containerRequest.getEntityStream())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return containerRequest;
    }

    // ─── Pre-existing S3 ListBucket coverage ───────────────────────────────

    @Test
    void resolvesS3ListBucketQueryConditionContext() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("prefix", "my_namespace/table/");
        query.add("delimiter", "/");
        query.add("max-keys", "100");

        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getQueryParameters()).thenReturn(query);

        Map<String, String> conditions = resolver.resolve("s3", "s3:ListBucket", containerRequest);

        assertEquals("my_namespace/table/", conditions.get("s3:prefix"));
        assertEquals("/", conditions.get("s3:delimiter"));
        assertEquals("100", conditions.get("s3:max-keys"));
    }

    @Test
    void s3BucketListConditionContextReturnsNullWhenNoSupportedQueryParametersArePresent() {
        assertNull(resolver.s3BucketListConditionContext(new MultivaluedHashMap<>()));
    }

    @Test
    void resolveReturnsNullForUnsupportedServiceOrAction() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        assertNull(resolver.resolve("lambda", "lambda:InvokeFunction", containerRequest));
        assertNull(resolver.resolve("s3", "s3:GetObject", containerRequest));
    }

    // ─── S3 bucket tagging: aws:RequestTag / aws:ResourceTag ──────────────

    @Test
    void putBucketTaggingResolvesRequestTagFromXmlBody() {
        String body = "<Tagging><TagSet>"
                + "<Tag><Key>Team</Key><Value>payments</Value></Tag>"
                + "<Tag><Key>Env</Key><Value>prod</Value></Tag>"
                + "</TagSet></Tagging>";
        ContainerRequestContext containerRequest = xmlRequest("/my-bucket", body);

        Map<String, String> conditions = resolver.resolve("s3", "s3:PutBucketTagging", containerRequest);

        assertEquals("payments", conditions.get("aws:RequestTag/Team"));
        assertEquals("prod", conditions.get("aws:RequestTag/Env"));
    }

    @Test
    void getBucketTaggingResolvesResourceTagFromExistingBucketTags() {
        when(s3Service.getBucketTagging("my-bucket")).thenReturn(Map.of("Owner", "alice"));
        ContainerRequestContext containerRequest = xmlRequest("/my-bucket", "");

        Map<String, String> conditions = resolver.resolve("s3", "s3:GetBucketTagging", containerRequest);

        assertEquals("alice", conditions.get("aws:ResourceTag/Owner"));
    }

    @Test
    void getBucketTaggingReturnsNullWhenBucketLookupFails() {
        when(s3Service.getBucketTagging(anyString()))
                .thenThrow(new RuntimeException("NoSuchBucket"));
        ContainerRequestContext containerRequest = xmlRequest("/missing-bucket", "");

        assertNull(resolver.resolve("s3", "s3:GetBucketTagging", containerRequest));
    }

    // ─── EC2: aws:RequestTag / aws:ResourceTag ─────────────────────────────

    @Test
    void runInstancesResolvesRequestTagFromTagSpecifications() {
        String body = "Action=RunInstances"
                + "&TagSpecification.1.ResourceType=instance"
                + "&TagSpecification.1.Tag.1.Key=Team"
                + "&TagSpecification.1.Tag.1.Value=payments";
        ContainerRequestContext containerRequest = formRequest("/", body);

        Map<String, String> conditions = resolver.resolve("ec2", "ec2:RunInstances", containerRequest);

        assertEquals("payments", conditions.get("aws:RequestTag/Team"));
    }

    @Test
    void createTagsResolvesBothRequestTagAndResourceTag() {
        when(ec2Service.effectiveTags("us-east-1", "i-0123456789"))
                .thenReturn(List.of(new Tag("Owner", "alice")));
        String body = "Action=CreateTags"
                + "&ResourceId.1=i-0123456789"
                + "&Tag.1.Key=Env"
                + "&Tag.1.Value=prod";
        ContainerRequestContext containerRequest = formRequest("/", body);

        Map<String, String> conditions = resolver.resolve("ec2", "ec2:CreateTags", containerRequest);

        assertEquals("prod", conditions.get("aws:RequestTag/Env"));
        assertEquals("alice", conditions.get("aws:ResourceTag/Owner"));
    }

    @Test
    void terminateInstancesResolvesResourceTagFromCurrentInstanceTags() {
        when(ec2Service.effectiveTags("us-east-1", "i-0123456789"))
                .thenReturn(List.of(new Tag("Team", "payments")));
        String body = "Action=TerminateInstances&InstanceId.1=i-0123456789";
        ContainerRequestContext containerRequest = formRequest("/", body);

        Map<String, String> conditions = resolver.resolve("ec2", "ec2:TerminateInstances", containerRequest);

        assertEquals("payments", conditions.get("aws:ResourceTag/Team"));
    }

    @Test
    void describeInstancesResolvesResourceTagWhenInstanceIdIsPresent() {
        when(ec2Service.effectiveTags("us-east-1", "i-0123456789"))
                .thenReturn(List.of(new Tag("Team", "payments")));
        String body = "Action=DescribeInstances&InstanceId.1=i-0123456789";
        ContainerRequestContext containerRequest = formRequest("/", body);

        Map<String, String> conditions = resolver.resolve("ec2", "ec2:DescribeInstances", containerRequest);

        assertEquals("payments", conditions.get("aws:ResourceTag/Team"));
    }

    @Test
    void describeInstancesReturnsNullWhenNoInstanceIdNamed() {
        String body = "Action=DescribeInstances";
        ContainerRequestContext containerRequest = formRequest("/", body);

        assertNull(resolver.resolve("ec2", "ec2:DescribeInstances", containerRequest));
    }

    @Test
    void ec2ConditionContextReturnsNullForUnhandledAction() {
        ContainerRequestContext containerRequest = formRequest("/", "Action=DescribeVpcs");

        assertNull(resolver.resolve("ec2", "ec2:DescribeVpcs", containerRequest));
    }

    // ─── End-to-end: resolver context feeds the real evaluator ────────────
    //
    // Before this class populated aws:ResourceTag / aws:RequestTag, resolve()
    // returned null for every one of these (service, action) pairs. With no
    // condition context, IamPolicyEvaluator.evaluate() treats every condition
    // key as absent, so a tag-conditioned Allow statement never matches and
    // the request falls through to implicit DENY — deny-everything, not the
    // scoped enforcement the policy asks for. These tests prove the fix by
    // running the SAME policy through the SAME evaluator, changing only
    // whether the target resource actually carries the tag.

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @Test
    void resourceTagConditionAllowsWhenEc2InstanceCarriesTheTagAndDeniesWhenItDoesNot() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:TerminateInstances","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Team":"payments"}}}
                ]}""";
        String body = "Action=TerminateInstances&InstanceId.1=i-0123456789";
        ContainerRequestContext containerRequest = formRequest("/", body);

        // Green: the instance carries the matching tag.
        when(ec2Service.effectiveTags("us-east-1", "i-0123456789"))
                .thenReturn(List.of(new Tag("Team", "payments")));
        Map<String, String> matchingCtx = resolver.resolve("ec2", "ec2:TerminateInstances", containerRequest);
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(CallerContext.of(List.of(policy)), null,
                        "ec2:TerminateInstances", "*", matchingCtx));

        // Red: same policy, but the instance carries a different tag value (or none).
        containerRequest = formRequest("/", body);
        when(ec2Service.effectiveTags("us-east-1", "i-0123456789"))
                .thenReturn(List.of(new Tag("Team", "engineering")));
        Map<String, String> mismatchedCtx = resolver.resolve("ec2", "ec2:TerminateInstances", containerRequest);
        assertEquals(Decision.DENY,
                evaluator.evaluate(CallerContext.of(List.of(policy)), null,
                        "ec2:TerminateInstances", "*", mismatchedCtx));
    }

    @Test
    void requestTagConditionAllowsCreateTagsWhenRequestedTagMatchesAndDeniesOtherwise() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:CreateTags","Resource":"*",
                   "Condition":{"StringEquals":{"aws:RequestTag/CostCenter":"1234"}}}
                ]}""";

        // Green: the caller is requesting the exact tag value the policy demands.
        ContainerRequestContext allowedRequest = formRequest("/",
                "Action=CreateTags&ResourceId.1=i-0123456789&Tag.1.Key=CostCenter&Tag.1.Value=1234");
        Map<String, String> matchingCtx = resolver.resolve("ec2", "ec2:CreateTags", allowedRequest);
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(CallerContext.of(List.of(policy)), null,
                        "ec2:CreateTags", "*", matchingCtx));

        // Red: same policy, but the caller requests a different CostCenter value.
        ContainerRequestContext deniedRequest = formRequest("/",
                "Action=CreateTags&ResourceId.1=i-0123456789&Tag.1.Key=CostCenter&Tag.1.Value=9999");
        Map<String, String> mismatchedCtx = resolver.resolve("ec2", "ec2:CreateTags", deniedRequest);
        assertEquals(Decision.DENY,
                evaluator.evaluate(CallerContext.of(List.of(policy)), null,
                        "ec2:CreateTags", "*", mismatchedCtx));
    }

    @Test
    void resourceTagConditionAllowsWhenS3BucketCarriesTheTagAndDeniesWhenItDoesNot() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetBucketTagging","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"alice"}}}
                ]}""";

        // Green.
        when(s3Service.getBucketTagging("my-bucket")).thenReturn(Map.of("Owner", "alice"));
        Map<String, String> matchingCtx =
                resolver.resolve("s3", "s3:GetBucketTagging", xmlRequest("/my-bucket", ""));
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(CallerContext.of(List.of(policy)), null,
                        "s3:GetBucketTagging", "*", matchingCtx));

        // Red: bucket exists but is owned by someone else.
        when(s3Service.getBucketTagging("my-bucket")).thenReturn(Map.of("Owner", "bob"));
        Map<String, String> mismatchedCtx =
                resolver.resolve("s3", "s3:GetBucketTagging", xmlRequest("/my-bucket", ""));
        assertEquals(Decision.DENY,
                evaluator.evaluate(CallerContext.of(List.of(policy)), null,
                        "s3:GetBucketTagging", "*", mismatchedCtx));
    }
}
