package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Model constraints shared by the AgentCore Evaluations operations on both planes: the control
 * plane's evaluators and online evaluation configurations, and the data plane's {@code Evaluate}
 * and batch evaluations. Patterns, lengths and enums come from the botocore models of
 * {@code bedrock-agentcore-control} and {@code bedrock-agentcore}.
 */
public final class BedrockAgentCoreEvaluationValidation {

    /** {@code EvaluatorId}: a built-in, a third-party, or a custom evaluator id. */
    public static final Pattern EVALUATOR_ID = Pattern.compile(
            "(Builtin\\.[a-zA-Z0-9._-]+|ThirdParty\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+|[a-zA-Z][a-zA-Z0-9-_]{0,99}-[a-zA-Z0-9]{10})");
    /** The id shape every AgentCore resource minted here uses: a name and ten alphanumerics. */
    public static final Pattern RESOURCE_ID = Pattern.compile("[a-zA-Z][a-zA-Z0-9-_]{0,99}-[a-zA-Z0-9]{10}");
    /** {@code CustomEvaluatorName}, {@code EvaluationConfigName} and {@code BatchEvaluationName}. */
    public static final Pattern RESOURCE_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,47}");
    public static final Pattern INSIGHT_ID = Pattern.compile(
            "(Builtin\\.[a-zA-Z0-9._-]+|[a-zA-Z][a-zA-Z0-9-_]{0,99}-[a-zA-Z0-9]{10})");
    public static final Pattern CLIENT_TOKEN = Pattern.compile("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,256}");
    public static final Pattern KMS_KEY_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":kms:[a-zA-Z0-9-]*:[0-9]{12}:key/[a-zA-Z0-9-]{36}");
    public static final Pattern ROLE_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::([0-9]{12})?:role/.+");
    public static final Pattern LAMBDA_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":lambda:([a-z]{2}(-gov)?-[a-z]+-\\d{1}):(\\d{12}):function:"
                    + "([a-zA-Z0-9-_.]+)(:(\\$LATEST|[a-zA-Z0-9-_]+))?");
    public static final Pattern ONLINE_EVALUATION_CONFIG_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":bedrock-agentcore:[a-z0-9-]+:[0-9]{12}:online-evaluation-config/"
                    + RESOURCE_ID.pattern());
    public static final Pattern LOG_GROUP_NAME = Pattern.compile("[.\\-_/#A-Za-z0-9]+");
    public static final Pattern OPTIONAL_LOG_GROUP_NAME = Pattern.compile("|[.\\-_/#A-Za-z0-9]+");
    public static final Pattern LOG_STREAM_NAME = Pattern.compile("[^:*]*");
    public static final Pattern SERVICE_NAME = Pattern.compile("[a-zA-Z0-9._-]+");
    public static final Pattern METRICS_NAMESPACE = Pattern.compile("[a-zA-Z0-9._#/:-]+");
    public static final Set<String> LEVELS = Set.of("TOOL_CALL", "TRACE", "SESSION");
    public static final Set<String> RESULT_DESTINATIONS = Set.of("DEDICATED_LOG_GROUP", "SOURCE_LOG_GROUP");

    private BedrockAgentCoreEvaluationValidation() {
    }

    /** Parses a request body that must be a JSON object; an empty body is an empty object. */
    public static JsonNode parseObject(ObjectMapper objectMapper, String body) throws IOException {
        JsonNode node = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
        if (!node.isObject()) {
            throw invalid("Request body must be a JSON object.");
        }
        return node;
    }

    public static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    /** True when the member is present and not JSON null. */
    public static boolean has(JsonNode node, String field) {
        return node != null && node.hasNonNull(field);
    }

    public static JsonNode requireMember(JsonNode node, String field, String path) {
        if (!has(node, field)) {
            throw invalid(path + field + " is required.");
        }
        return node.get(field);
    }

    public static JsonNode requireObject(JsonNode node, String field, String path) {
        JsonNode value = requireMember(node, field, path);
        if (!value.isObject()) {
            throw invalid(path + field + " must be an object.");
        }
        return value;
    }

    /** Returns the object at {@code field}, or null when absent; a non-object is refused. */
    public static JsonNode optionalObject(JsonNode node, String field, String path) {
        if (!has(node, field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isObject()) {
            throw invalid(path + field + " must be an object.");
        }
        return value;
    }

    /**
     * Validates a string member against its modelled bounds. Returns the value, or null when the
     * member is absent and not required. A null pattern or a negative bound means unconstrained.
     */
    public static String string(JsonNode node, String field, String path, boolean required,
                                int min, int max, Pattern pattern) {
        if (!has(node, field)) {
            if (required) {
                throw invalid(path + field + " is required.");
            }
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isTextual()) {
            throw invalid(path + field + " must be a string.");
        }
        String text = value.asText();
        if (min >= 0 && text.length() < min) {
            throw invalid(path + field + " must be at least " + min + " characters.");
        }
        if (max >= 0 && text.length() > max) {
            throw invalid(path + field + " must be at most " + max + " characters.");
        }
        if (pattern != null && !pattern.matcher(text).matches()) {
            throw invalid(path + field + " must match the pattern " + pattern.pattern() + ".");
        }
        return text;
    }

    public static String enumValue(JsonNode node, String field, String path, boolean required, Set<String> values) {
        String value = string(node, field, path, required, -1, -1, null);
        if (value != null && !values.contains(value)) {
            throw invalid(path + field + " must be one of " + values.stream().sorted().toList() + ".");
        }
        return value;
    }

    /** Validates a numeric member against an inclusive range. Null bounds mean unconstrained. */
    public static void number(JsonNode node, String field, String path, boolean required,
                              Double min, Double max, boolean integral) {
        if (!has(node, field)) {
            if (required) {
                throw invalid(path + field + " is required.");
            }
            return;
        }
        JsonNode value = node.get(field);
        if (!value.isNumber() || (integral && !value.canConvertToLong())) {
            throw invalid(path + field + " must be " + (integral ? "an integer." : "a number."));
        }
        double d = value.asDouble();
        if ((min != null && d < min) || (max != null && d > max)) {
            throw invalid(path + field + " must be between " + min + " and " + max + ".");
        }
    }

    public static void bool(JsonNode node, String field, String path, boolean required) {
        if (!has(node, field)) {
            if (required) {
                throw invalid(path + field + " is required.");
            }
            return;
        }
        if (!node.get(field).isBoolean()) {
            throw invalid(path + field + " must be a boolean.");
        }
    }

    /** Validates a list member's size and returns its elements; an absent optional list is empty. */
    public static List<JsonNode> list(JsonNode node, String field, String path, boolean required, int min, int max) {
        if (!has(node, field)) {
            if (required) {
                throw invalid(path + field + " is required.");
            }
            return List.of();
        }
        JsonNode value = node.get(field);
        if (!value.isArray()) {
            throw invalid(path + field + " must be a list.");
        }
        if ((min >= 0 && value.size() < min) || (max >= 0 && value.size() > max)) {
            throw invalid(path + field + " must have between " + Math.max(min, 0) + " and "
                    + (max >= 0 ? max : "any number of") + " items.");
        }
        List<JsonNode> items = new ArrayList<>();
        value.forEach(items::add);
        return items;
    }

    /** Validates every element of a string list. */
    public static void stringList(JsonNode node, String field, String path, boolean required,
                                  int minItems, int maxItems, int min, int max, Pattern pattern) {
        List<JsonNode> items = list(node, field, path, required, minItems, maxItems);
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!item.isTextual()) {
                throw invalid(path + field + "[" + i + "] must be a string.");
            }
            String text = item.asText();
            if ((min >= 0 && text.length() < min) || (max >= 0 && text.length() > max)
                    || (pattern != null && !pattern.matcher(text).matches())) {
                throw invalid(path + field + "[" + i + "] does not satisfy its constraints.");
            }
        }
    }

    /**
     * A union member carries exactly one of its alternatives. Returns the name of the one set;
     * a member the model does not define is refused as well.
     */
    public static String union(JsonNode value, String path, Set<String> members) {
        if (value == null || !value.isObject()) {
            throw invalid(path + " must be an object.");
        }
        String chosen = null;
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (value.get(name).isNull()) {
                continue;
            }
            if (!members.contains(name)) {
                throw invalid(path + " has an unknown member " + name + ".");
            }
            if (chosen != null) {
                throw invalid(path + " must set exactly one of " + members.stream().sorted().toList() + ".");
            }
            chosen = name;
        }
        if (chosen == null) {
            throw invalid(path + " must set exactly one of " + members.stream().sorted().toList() + ".");
        }
        return chosen;
    }

    public static void clientToken(JsonNode req) {
        string(req, "clientToken", "", false, 33, 256, CLIENT_TOKEN);
    }

    public static void kmsKeyArn(JsonNode req) {
        string(req, "kmsKeyArn", "", false, 1, 2048, KMS_KEY_ARN);
    }

    /** {@code TagsMap}: up to 50 entries, keys 1 to 128 and values up to 256 characters. */
    public static void tags(JsonNode req) {
        BedrockAgentCoreTagValidation.validateTags(req.get("tags"));
    }

    /** {@code Insight} list entries: {@code insightId} is required and has its own id shape. */
    public static void insights(JsonNode req, String path, int max) {
        List<JsonNode> insights = list(req, "insights", path, false, 0, max);
        for (int i = 0; i < insights.size(); i++) {
            string(insights.get(i), "insightId", path + "insights[" + i + "].", true, -1, -1, INSIGHT_ID);
        }
    }
}
