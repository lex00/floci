package io.github.hectorvent.floci.services.arcregionswitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArcRegionSwitchExecutionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ArcRegionSwitchService plans = new ArcRegionSwitchService(new InMemoryStorage<>(),
            new RegionResolver("us-east-1", "000000000000"));
    private final ArcRegionSwitchExecutionService service =
            new ArcRegionSwitchExecutionService(new InMemoryStorage<>(), plans);

    private String plan() throws Exception {
        return plans.createPlan("p", List.of("us-east-1", "us-west-2"), "activePassive", "us-east-1",
                new ArcRegionSwitchService.PlanSettings(null, mapper.readTree("""
                        [{"workflowTargetAction":"deactivate","steps":[{"name":"s","executionBlockType":"CustomActionLambda",
                          "executionBlockConfiguration":{}}]}]"""), "arn:aws:iam::000000000000:role/r",
                        null, null, null, null, null), Map.of()).getArn();
    }

    @Test
    void executionWithoutApprovalCompletesAtOnce() throws Exception {
        String arn = plan();
        String id = service.startPlanExecution(mapper.readTree("{\"planArn\":\"" + arn
                + "\",\"targetRegion\":\"us-east-1\",\"action\":\"deactivate\"}")).path("executionId").asText();

        assertEquals("completed", service.getPlanExecution(arn, id).path("executionState").asText());
        assertEquals("completed", service.getPlanExecution(arn, id).path("stepStates").get(0).path("status").asText());
    }

    @Test
    void targetRegionMustBelongToThePlan() throws Exception {
        String arn = plan();
        AwsException wrong = assertThrows(AwsException.class, () -> service.startPlanExecution(mapper.readTree(
                "{\"planArn\":\"" + arn + "\",\"targetRegion\":\"eu-west-1\",\"action\":\"activate\"}")));
        assertEquals("IllegalArgumentException", wrong.getErrorCode());
    }
}
