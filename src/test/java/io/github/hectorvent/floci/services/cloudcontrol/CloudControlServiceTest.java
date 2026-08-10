package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationResourceProvisioner;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudControlServiceTest {

    @Test
    void emitsOnlyAwsShapedTagsForMalformedPersistedData() throws Exception {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-test");
        vpc.setTags(List.of(
                new Tag(null, "ignored-null"),
                new Tag("", "ignored-empty"),
                new Tag("  ", "ignored-blank"),
                new Tag("Name", null)));
        when(ec2Service.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of(vpc));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), ec2Service, mock(IamService.class),
                mock(CloudFormationResourceProvisioner.class), mapper);

        String properties = service.listResources("us-east-1", "AWS::EC2::VPC").getFirst().properties();
        JsonNode tags = mapper.readTree(properties).path("Tags");

        assertTrue(tags.isArray());
        assertEquals(1, tags.size());
        assertTrue(tags.get(0).path("Key").isTextual());
        assertEquals("Name", tags.get(0).path("Key").asText());
        assertTrue(tags.get(0).path("Value").isTextual());
        assertEquals("", tags.get(0).path("Value").asText());
        assertFalse(properties.contains("ignored-null"));
        assertFalse(properties.contains("ignored-empty"));
        assertFalse(properties.contains("ignored-blank"));
    }

    @Test
    void roleReadModelCarriesTrustPolicyAndAttachments() throws Exception {
        IamService iamService = mock(IamService.class);
        IamRole role = new IamRole("AROATEST", "qa-role", "/",
                "arn:aws:iam::000000000000:role/qa-role",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"sts:AssumeRole\"}]}");
        role.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/ReadOnlyAccess");
        when(iamService.listRoles("/")).thenReturn(List.of(role));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), iamService,
                mock(CloudFormationResourceProvisioner.class), mapper);

        JsonNode props = mapper.readTree(
                service.listResources("us-east-1", "AWS::IAM::Role").getFirst().properties());

        // A drift engine diffs the declared role against this model; identity
        // alone read as the whole trust policy having been removed.
        assertEquals("2012-10-17", props.path("AssumeRolePolicyDocument").path("Version").asText());
        assertEquals("Allow",
                props.path("AssumeRolePolicyDocument").path("Statement").get(0).path("Effect").asText());
        assertEquals("arn:aws:iam::aws:policy/ReadOnlyAccess",
                props.path("ManagedPolicyArns").get(0).asText());
    }

    @Test
    void managedPoliciesListWithArnAsIdentifier() throws Exception {
        IamService iamService = mock(IamService.class);
        IamPolicy policy = new IamPolicy("ANPATEST", "S3VectorsReadOnlyAccess", "/",
                "arn:aws:iam::000000000000:policy/S3VectorsReadOnlyAccess",
                null, "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        when(iamService.listPolicies("Local", "/")).thenReturn(List.of(policy));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), iamService,
                mock(CloudFormationResourceProvisioner.class), mapper);

        var described = service.listResources("us-east-1", "AWS::IAM::ManagedPolicy").getFirst();

        // The ARN is the Cloud Control identifier, so a GetResource by ARN —
        // how CloudFormation names a managed policy — resolves instead of
        // reporting ResourceNotFound over a policy that exists.
        assertEquals("arn:aws:iam::000000000000:policy/S3VectorsReadOnlyAccess", described.identifier());
        JsonNode props = mapper.readTree(described.properties());
        assertEquals("S3VectorsReadOnlyAccess", props.path("ManagedPolicyName").asText());
        assertEquals("2012-10-17", props.path("PolicyDocument").path("Version").asText());
    }
}
