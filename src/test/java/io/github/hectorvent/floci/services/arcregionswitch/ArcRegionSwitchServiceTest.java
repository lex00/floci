package io.github.hectorvent.floci.services.arcregionswitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.arcregionswitch.model.RegionSwitchPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcRegionSwitchServiceTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/switch";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArcRegionSwitchService service = new ArcRegionSwitchService(
            new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"));

    private ArcRegionSwitchService.PlanSettings settings(String description) throws Exception {
        return new ArcRegionSwitchService.PlanSettings(description,
                mapper.readTree("[{\"workflowTargetAction\":\"activate\",\"steps\":[]}]"), ROLE,
                null, null, null, null, null);
    }

    @Test
    void planArnIsRegionlessWithASixCharacterSuffix() throws Exception {
        RegionSwitchPlan plan = service.createPlan("my-plan", List.of("us-east-1", "us-west-2"),
                "activePassive", "us-east-1", settings(null), Map.of("env", "test"));

        assertTrue(plan.getArn().matches("arn:aws:arc-region-switch::000000000000:plan/my-plan:[a-z0-9]{6}"),
                plan.getArn());
        assertEquals("000000000000", plan.getOwner());
        assertEquals(1, plan.getVersion());
        assertEquals(1, service.listPlans().size());
    }

    @Test
    void createRequiresTwoDistinctRegions() throws Exception {
        AwsException e = assertThrows(AwsException.class, () -> service.createPlan("p",
                List.of("us-east-1"), "activeActive", null, settings(null), Map.of()));
        assertEquals("IllegalArgumentException", e.getErrorCode());
        assertThrows(AwsException.class, () -> service.createPlan("p",
                List.of("us-east-1", "us-east-1"), "activeActive", null, settings(null), Map.of()));
    }

    @Test
    void primaryRegionMustBeOneOfThePlanRegions() throws Exception {
        assertThrows(AwsException.class, () -> service.createPlan("p", List.of("us-east-1", "us-west-2"),
                "activePassive", "eu-west-1", settings(null), Map.of()));
    }

    @Test
    void updateReplacesSettingsAndBumpsTheVersion() throws Exception {
        RegionSwitchPlan plan = service.createPlan("p", List.of("us-east-1", "us-west-2"),
                "activeActive", null, settings("one"), Map.of());

        RegionSwitchPlan updated = service.updatePlan(plan.getArn(), settings("two"));

        assertEquals("two", updated.getDescription());
        assertEquals(2, updated.getVersion());
    }

    @Test
    void deletedPlanIsNotFound() throws Exception {
        RegionSwitchPlan plan = service.createPlan("p", List.of("us-east-1", "us-west-2"),
                "activeActive", null, settings(null), Map.of());
        service.deletePlan(plan.getArn());

        AwsException e = assertThrows(AwsException.class, () -> service.getPlan(plan.getArn()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }
}
