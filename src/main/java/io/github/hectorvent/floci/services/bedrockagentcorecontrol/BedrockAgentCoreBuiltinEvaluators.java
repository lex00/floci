package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The AWS-managed {@code Builtin.*} evaluators that {@code ListEvaluators} returns next to an
 * account's own. Ids and levels are taken from the AgentCore developer guide: the built-in prompt
 * templates, the skill evaluators and the ground-truth trajectory evaluators. They are global
 * resources, so their ARN carries neither region nor account
 * ({@code arn:<partition>:bedrock-agentcore:::evaluator/Builtin.Helpfulness}).
 */
public final class BedrockAgentCoreBuiltinEvaluators {

    public static final String PREFIX = "Builtin.";
    /** Built-ins predate every account; a fixed instant keeps list output stable across restarts. */
    public static final Instant CREATED_AT = Instant.parse("2025-12-02T00:00:00Z");

    public record Builtin(String id, String level, String description) {
    }

    private static final Map<String, Builtin> BUILTINS = new LinkedHashMap<>();

    static {
        add("Builtin.GoalSuccessRate", "SESSION", "Evaluates whether the user's goals were met across the session.");
        add("Builtin.TrajectoryExactOrderMatch", "SESSION",
                "Checks that the tool call sequence matches the expected trajectory exactly.");
        add("Builtin.TrajectoryInOrderMatch", "SESSION",
                "Checks that the expected tools appear in order, allowing extra tools between them.");
        add("Builtin.TrajectoryAnyOrderMatch", "SESSION",
                "Checks that every expected tool appears, in any order.");
        add("Builtin.Coherence", "TRACE", "Evaluates the logical coherence of the response.");
        add("Builtin.Conciseness", "TRACE", "Evaluates whether the response is appropriately concise.");
        add("Builtin.Correctness", "TRACE", "Evaluates whether the response is factually correct.");
        add("Builtin.Faithfulness", "TRACE", "Evaluates whether the response is faithful to the conversation history.");
        add("Builtin.Harmfulness", "TRACE", "Evaluates whether the response contains harmful content.");
        add("Builtin.Helpfulness", "TRACE", "Evaluates how helpful the response is to the user.");
        add("Builtin.InstructionFollowing", "TRACE", "Evaluates whether the response follows the instructions given.");
        add("Builtin.Refusal", "TRACE", "Evaluates whether the response refuses to answer.");
        add("Builtin.ResponseRelevance", "TRACE", "Evaluates whether the response is relevant to the request.");
        add("Builtin.Stereotyping", "TRACE", "Evaluates whether the response contains stereotyping.");
        add("Builtin.ToolParameterAccuracy", "TOOL_CALL", "Evaluates whether tool call parameters are accurate.");
        add("Builtin.ToolSelectionAccuracy", "TOOL_CALL", "Evaluates whether the appropriate tool was selected.");
        add("Builtin.SkillSelectionAccuracy", "TOOL_CALL", "Evaluates whether the loaded skill fits the task.");
        add("Builtin.SkillInstructionFollowing", "TOOL_CALL",
                "Evaluates how fully the agent followed the loaded skill's steps.");
    }

    private BedrockAgentCoreBuiltinEvaluators() {
    }

    private static void add(String id, String level, String description) {
        BUILTINS.put(id, new Builtin(id, level, description));
    }

    public static List<Builtin> all() {
        return List.copyOf(BUILTINS.values());
    }

    public static Optional<Builtin> find(String id) {
        return Optional.ofNullable(BUILTINS.get(id));
    }

    public static boolean isBuiltinId(String id) {
        return id != null && id.startsWith(PREFIX);
    }
}
