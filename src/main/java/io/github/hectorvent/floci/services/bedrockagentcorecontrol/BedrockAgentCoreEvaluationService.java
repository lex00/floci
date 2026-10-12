package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Evaluator;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.OnlineEvaluationConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.EVALUATOR_ID;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.LAMBDA_ARN;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.LEVELS;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.LOG_GROUP_NAME;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.METRICS_NAMESPACE;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.OPTIONAL_LOG_GROUP_NAME;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.RESOURCE_ID;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.RESOURCE_NAME;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.RESULT_DESTINATIONS;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.ROLE_ARN;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.SERVICE_NAME;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.has;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.invalid;

/**
 * AgentCore Evaluations control plane: custom evaluators, the AWS built-in evaluators listed
 * beside them, and online evaluation configurations. A metadata registry only: no judge model
 * runs and no live traffic is sampled.
 *
 * <p>An evaluator referenced by an online evaluation configuration whose execution status is
 * {@code ENABLED} is locked, as on AWS: it reports {@code lockedForModification}, and updating or
 * deleting it is a {@code ConflictException} until the configuration is disabled or deleted.
 */
@ApplicationScoped
public class BedrockAgentCoreEvaluationService {

    static final String ARN_SERVICE = "bedrock-agentcore";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_DELETING = "DELETING";
    static final String EXECUTION_ENABLED = "ENABLED";
    static final String EXECUTION_DISABLED = "DISABLED";
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_PAGE = 100;
    private static final Set<String> THIRD_PARTY_PROVIDERS = Set.of("DeepEval", "AutoEval");

    private final StorageBackend<String, Evaluator> evaluators;
    private final StorageBackend<String, OnlineEvaluationConfig> onlineConfigs;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Inject
    public BedrockAgentCoreEvaluationService(StorageFactory storageFactory, RegionResolver regionResolver,
                                             ObjectMapper objectMapper, Clock clock) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-evaluators.json",
                        new TypeReference<Map<String, Evaluator>>() {}),
                storageFactory.create("bedrockagentcore", "bedrock-agentcore-online-evaluation-configs.json",
                        new TypeReference<Map<String, OnlineEvaluationConfig>>() {}),
                regionResolver, objectMapper, clock);
    }

    /** Public because the data plane's tests, in a sibling package, build one over plain storage. */
    public BedrockAgentCoreEvaluationService(StorageBackend<String, Evaluator> evaluators,
                                             StorageBackend<String, OnlineEvaluationConfig> onlineConfigs,
                                             RegionResolver regionResolver, ObjectMapper objectMapper,
                                             Clock clock) {
        this.evaluators = evaluators;
        this.onlineConfigs = onlineConfigs;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ── Evaluators ──

    public Evaluator createEvaluator(JsonNode req, String region) {
        BedrockAgentCoreEvaluationValidation.clientToken(req);
        String clientToken = BedrockAgentCoreEvaluationValidation.string(req, "clientToken", "", false, -1, -1, null);
        if (clientToken != null) {
            Optional<Evaluator> replay = evaluators.scan(k -> k.startsWith(evaluatorPrefix(region))).stream()
                    .filter(e -> clientToken.equals(e.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        String name = BedrockAgentCoreEvaluationValidation.string(req, "evaluatorName", "", true, -1, -1, RESOURCE_NAME);
        String description = BedrockAgentCoreEvaluationValidation.string(req, "description", "", false, 1, 200, null);
        JsonNode config = BedrockAgentCoreEvaluationValidation.requireMember(req, "evaluatorConfig", "");
        String kind = validateEvaluatorConfig(config);
        String level = BedrockAgentCoreEvaluationValidation.enumValue(req, "level", "", true, LEVELS);
        BedrockAgentCoreEvaluationValidation.kmsKeyArn(req);
        BedrockAgentCoreEvaluationValidation.tags(req);

        boolean taken = evaluators.scan(k -> k.startsWith(evaluatorPrefix(region))).stream()
                .anyMatch(e -> name.equals(e.getEvaluatorName()));
        if (taken) {
            throw new AwsException("ConflictException", "An evaluator named " + name + " already exists.", 409);
        }

        Instant now = Instant.now(clock);
        Evaluator evaluator = new Evaluator();
        evaluator.setEvaluatorId(name + "-" + random(10));
        evaluator.setEvaluatorName(name);
        evaluator.setDescription(description);
        applyConfig(evaluator, config, kind, level);
        evaluator.setStatus(STATUS_ACTIVE);
        evaluator.setKmsKeyArn(BedrockAgentCoreControllerSupport.text(req, "kmsKeyArn"));
        evaluator.setCreatedAt(now);
        evaluator.setUpdatedAt(now);
        evaluator.setClientToken(clientToken);
        Map<String, String> tags = BedrockAgentCoreControllerSupport.stringMap(req.get("tags"));
        if (tags != null) {
            evaluator.getTags().putAll(tags);
        }
        evaluators.put(evaluatorKey(region, evaluator.getEvaluatorId()), evaluator);
        return evaluator;
    }

    /** A custom evaluator from storage, or a built-in rendered on the fly. */
    public Evaluator getEvaluator(String id, String region) {
        return findEvaluator(id, region).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Evaluator not found: " + id, 404));
    }

    /**
     * Resolves an evaluator reference for the data plane and for online configurations. A
     * {@code ThirdParty.*} id names an AWS-managed evaluator from an open-source library, which
     * this emulator does not list but accepts wherever an id is expected.
     */
    public Optional<Evaluator> findEvaluator(String id, String region) {
        requireEvaluatorId(id);
        if (BedrockAgentCoreBuiltinEvaluators.isBuiltinId(id)) {
            return BedrockAgentCoreBuiltinEvaluators.find(id).map(this::builtin);
        }
        if (id.startsWith("ThirdParty.")) {
            return Optional.of(thirdParty(id));
        }
        return evaluators.get(evaluatorKey(region, id));
    }

    public Evaluator updateEvaluator(String id, JsonNode req, String region) {
        requireEvaluatorId(id);
        if (BedrockAgentCoreBuiltinEvaluators.isBuiltinId(id) || id.startsWith("ThirdParty.")) {
            throw invalid("Built-in evaluators cannot be updated.");
        }
        BedrockAgentCoreEvaluationValidation.clientToken(req);
        String description = BedrockAgentCoreEvaluationValidation.string(req, "description", "", false, 1, 200, null);
        JsonNode config = has(req, "evaluatorConfig") ? req.get("evaluatorConfig") : null;
        String kind = config == null ? null : validateEvaluatorConfig(config);
        String level = BedrockAgentCoreEvaluationValidation.enumValue(req, "level", "", false, LEVELS);
        BedrockAgentCoreEvaluationValidation.kmsKeyArn(req);

        Evaluator evaluator = getEvaluator(id, region);
        if (isLocked(evaluator, region)) {
            throw new AwsException("ConflictException",
                    "Evaluator " + id + " is locked by an enabled online evaluation configuration.", 409);
        }
        if (description != null) {
            evaluator.setDescription(description);
        }
        if (config != null) {
            applyConfig(evaluator, config, kind, level != null ? level : evaluator.getLevel());
        } else if (level != null && !"derived".equals(configKind(evaluator))) {
            evaluator.setLevel(level);
        }
        if (has(req, "kmsKeyArn")) {
            evaluator.setKmsKeyArn(req.get("kmsKeyArn").asText());
        }
        evaluator.setStatus(STATUS_ACTIVE);
        evaluator.setUpdatedAt(Instant.now(clock));
        evaluators.put(evaluatorKey(region, id), evaluator);
        return evaluator;
    }

    public Evaluator deleteEvaluator(String id, String region) {
        requireEvaluatorId(id);
        if (BedrockAgentCoreBuiltinEvaluators.isBuiltinId(id) || id.startsWith("ThirdParty.")) {
            throw invalid("Built-in evaluators cannot be deleted.");
        }
        Evaluator evaluator = getEvaluator(id, region);
        if (isLocked(evaluator, region)) {
            throw new AwsException("ConflictException",
                    "Evaluator " + id + " is referenced by an enabled online evaluation configuration.", 409);
        }
        evaluators.delete(evaluatorKey(region, id));
        evaluator.setStatus(STATUS_DELETING);
        return evaluator;
    }

    /** Built-ins first in id order, then the account's own evaluators in the same region. */
    public PaginatedResult<Evaluator> listEvaluators(Integer maxResults, String nextToken, String region) {
        List<Evaluator> all = new ArrayList<>();
        BedrockAgentCoreBuiltinEvaluators.all().forEach(b -> all.add(builtin(b)));
        all.addAll(evaluators.scan(k -> k.startsWith(evaluatorPrefix(region))));
        return Pagination.paginate(all, Evaluator::getEvaluatorId, maxResults, nextToken, MAX_PAGE,
                "ValidationException");
    }

    public boolean isLocked(Evaluator evaluator, String region) {
        if (!isCustom(evaluator)) {
            return false;
        }
        String id = evaluator.getEvaluatorId();
        return onlineConfigs.scan(k -> k.startsWith(onlinePrefix(region))).stream()
                .filter(c -> EXECUTION_ENABLED.equals(c.getExecutionStatus()))
                .anyMatch(c -> referencedIds(c.getEvaluators()).contains(id));
    }

    public static boolean isCustom(Evaluator evaluator) {
        return evaluator.getEvaluatorType() != null && evaluator.getEvaluatorType().startsWith("Custom");
    }

    public String evaluatorArn(Evaluator evaluator, String region) {
        if (!isCustom(evaluator)) {
            return AwsArnUtils.Arn.global(regionResolver.partitionForRegion(region), ARN_SERVICE, "",
                    "evaluator/" + evaluator.getEvaluatorId()).toString();
        }
        return regionResolver.buildArn(ARN_SERVICE, region, "evaluator/" + evaluator.getEvaluatorId());
    }

    private Evaluator builtin(BedrockAgentCoreBuiltinEvaluators.Builtin b) {
        Evaluator evaluator = new Evaluator();
        evaluator.setEvaluatorId(b.id());
        evaluator.setEvaluatorName(b.id());
        evaluator.setDescription(b.description());
        evaluator.setEvaluatorType("Builtin");
        evaluator.setProvider("AWS");
        evaluator.setLevel(b.level());
        evaluator.setStatus(STATUS_ACTIVE);
        // Built-in configurations (judge model and prompt) are AWS-owned and never returned.
        evaluator.setEvaluatorConfig(objectMapper.createObjectNode());
        evaluator.setCreatedAt(BedrockAgentCoreBuiltinEvaluators.CREATED_AT);
        evaluator.setUpdatedAt(BedrockAgentCoreBuiltinEvaluators.CREATED_AT);
        return evaluator;
    }

    private Evaluator thirdParty(String id) {
        Evaluator evaluator = new Evaluator();
        evaluator.setEvaluatorId(id);
        evaluator.setEvaluatorName(id);
        evaluator.setEvaluatorType("ThirdParty");
        evaluator.setProvider(thirdPartyProvider(id));
        evaluator.setLevel("TRACE");
        evaluator.setStatus(STATUS_ACTIVE);
        evaluator.setEvaluatorConfig(objectMapper.createObjectNode());
        evaluator.setCreatedAt(BedrockAgentCoreBuiltinEvaluators.CREATED_AT);
        evaluator.setUpdatedAt(BedrockAgentCoreBuiltinEvaluators.CREATED_AT);
        return evaluator;
    }

    private static String thirdPartyProvider(String id) {
        String[] parts = id.split("\\.");
        return parts.length > 1 && THIRD_PARTY_PROVIDERS.contains(parts[1]) ? parts[1] : "Custom";
    }

    /**
     * Sets the configuration and the members derived from it. The type and provider follow the
     * configuration kind, and a derived evaluator takes its level from a built-in base, which the
     * developer guide documents as read-only.
     */
    private void applyConfig(Evaluator evaluator, JsonNode config, String kind, String level) {
        evaluator.setEvaluatorConfig(config.deepCopy());
        evaluator.setLevel(level);
        switch (kind) {
            case "llmAsAJudge" -> {
                evaluator.setEvaluatorType("Custom");
                evaluator.setProvider("Custom");
            }
            case "codeBased" -> {
                evaluator.setEvaluatorType("CustomCode");
                evaluator.setProvider("Custom");
            }
            default -> {
                String base = config.get("derived").get("baseEvaluatorId").asText();
                evaluator.setEvaluatorType("CustomDerived");
                if (BedrockAgentCoreBuiltinEvaluators.isBuiltinId(base)) {
                    evaluator.setProvider("AWS");
                    BedrockAgentCoreBuiltinEvaluators.find(base).ifPresent(b -> evaluator.setLevel(b.level()));
                } else if (base.startsWith("ThirdParty.")) {
                    evaluator.setProvider(thirdPartyProvider(base));
                } else {
                    evaluator.setProvider("Custom");
                }
            }
        }
    }

    private static String configKind(Evaluator evaluator) {
        JsonNode config = evaluator.getEvaluatorConfig();
        if (config == null || !config.fieldNames().hasNext()) {
            return null;
        }
        return config.fieldNames().next();
    }

    /** Validates an {@code EvaluatorConfig} union and returns the member it sets. */
    String validateEvaluatorConfig(JsonNode config) {
        String path = "evaluatorConfig";
        String kind = BedrockAgentCoreEvaluationValidation.union(config, path,
                Set.of("llmAsAJudge", "codeBased", "derived"));
        JsonNode body = config.get(kind);
        String p = path + "." + kind + ".";
        switch (kind) {
            case "llmAsAJudge" -> {
                BedrockAgentCoreEvaluationValidation.string(body, "instructions", p, true, -1, -1, null);
                JsonNode scale = BedrockAgentCoreEvaluationValidation.requireMember(body, "ratingScale", p);
                String scaleKind = BedrockAgentCoreEvaluationValidation.union(scale, p + "ratingScale",
                        Set.of("numerical", "categorical"));
                List<JsonNode> entries = BedrockAgentCoreEvaluationValidation.list(scale, scaleKind,
                        p + "ratingScale.", true, -1, -1);
                for (int i = 0; i < entries.size(); i++) {
                    String ep = p + "ratingScale." + scaleKind + "[" + i + "].";
                    JsonNode entry = entries.get(i);
                    BedrockAgentCoreEvaluationValidation.string(entry, "definition", ep, true, -1, -1, null);
                    BedrockAgentCoreEvaluationValidation.string(entry, "label", ep, true, 1, 100, null);
                    if ("numerical".equals(scaleKind)) {
                        BedrockAgentCoreEvaluationValidation.number(entry, "value", ep, true, 0.0, null, false);
                    }
                }
                validateModelConfig(BedrockAgentCoreEvaluationValidation.requireMember(body, "modelConfig", p),
                        p + "modelConfig");
            }
            case "codeBased" -> {
                BedrockAgentCoreEvaluationValidation.union(body, path + ".codeBased", Set.of("lambdaConfig"));
                JsonNode lambda = body.get("lambdaConfig");
                String lp = p + "lambdaConfig.";
                BedrockAgentCoreEvaluationValidation.string(lambda, "lambdaArn", lp, true, -1, -1, LAMBDA_ARN);
                BedrockAgentCoreEvaluationValidation.number(lambda, "lambdaTimeoutInSeconds", lp, false,
                        1.0, 300.0, true);
            }
            default -> {
                String base = BedrockAgentCoreEvaluationValidation.string(body, "baseEvaluatorId", p, true,
                        1, 111, EVALUATOR_ID);
                if (BedrockAgentCoreBuiltinEvaluators.isBuiltinId(base)
                        && BedrockAgentCoreBuiltinEvaluators.find(base).isEmpty()) {
                    throw invalid("Unknown built-in evaluator: " + base);
                }
                validateModelConfig(BedrockAgentCoreEvaluationValidation.requireMember(body, "modelConfig", p),
                        p + "modelConfig");
            }
        }
        return kind;
    }

    private static void validateModelConfig(JsonNode modelConfig, String path) {
        String kind = BedrockAgentCoreEvaluationValidation.union(modelConfig, path,
                Set.of("bedrockEvaluatorModelConfig", "responsesEvaluatorModelConfig"));
        JsonNode body = modelConfig.get(kind);
        String p = path + "." + kind + ".";
        BedrockAgentCoreEvaluationValidation.string(body, "modelId", p, true, -1, -1, null);
        if ("bedrockEvaluatorModelConfig".equals(kind)) {
            JsonNode inference = BedrockAgentCoreEvaluationValidation.optionalObject(body, "inferenceConfig", p);
            if (inference != null) {
                String ip = p + "inferenceConfig.";
                BedrockAgentCoreEvaluationValidation.number(inference, "maxTokens", ip, false, 1.0, null, true);
                BedrockAgentCoreEvaluationValidation.number(inference, "temperature", ip, false, 0.0, 1.0, false);
                BedrockAgentCoreEvaluationValidation.number(inference, "topP", ip, false, 0.0, 1.0, false);
                BedrockAgentCoreEvaluationValidation.stringList(inference, "stopSequences", ip, false,
                        0, 2500, 1, -1, null);
            }
        } else {
            BedrockAgentCoreEvaluationValidation.number(body, "maxOutputTokens", p, false, 1.0, null, true);
            BedrockAgentCoreEvaluationValidation.number(body, "temperature", p, false, 0.0, 2.0, false);
            BedrockAgentCoreEvaluationValidation.number(body, "topP", p, false, 0.0, 1.0, false);
            JsonNode reasoning = BedrockAgentCoreEvaluationValidation.optionalObject(body, "reasoning", p);
            if (reasoning != null) {
                BedrockAgentCoreEvaluationValidation.string(reasoning, "effort", p + "reasoning.", false,
                        1, 64, null);
            }
        }
    }

    // ── Online evaluation configurations ──

    public OnlineEvaluationConfig createOnlineConfig(JsonNode req, String region) {
        BedrockAgentCoreEvaluationValidation.clientToken(req);
        String clientToken = BedrockAgentCoreEvaluationValidation.string(req, "clientToken", "", false, -1, -1, null);
        if (clientToken != null) {
            Optional<OnlineEvaluationConfig> replay = onlineConfigs.scan(k -> k.startsWith(onlinePrefix(region)))
                    .stream()
                    .filter(c -> clientToken.equals(c.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        String name = BedrockAgentCoreEvaluationValidation.string(req, "onlineEvaluationConfigName", "", true,
                -1, -1, RESOURCE_NAME);
        String description = BedrockAgentCoreEvaluationValidation.string(req, "description", "", false, 1, 200, null);
        validateRule(BedrockAgentCoreEvaluationValidation.requireMember(req, "rule", ""));
        validateControlDataSource(BedrockAgentCoreEvaluationValidation.requireMember(req, "dataSourceConfig", ""));
        validateEvaluatorReferences(req, region);
        BedrockAgentCoreEvaluationValidation.insights(req, "", 10);
        validateClusteringConfig(req);
        validateOutputConfig(req);
        String role = BedrockAgentCoreEvaluationValidation.string(req, "evaluationExecutionRoleArn", "", true,
                1, 2048, ROLE_ARN);
        BedrockAgentCoreEvaluationValidation.bool(req, "enableOnCreate", "", true);
        BedrockAgentCoreEvaluationValidation.tags(req);

        boolean taken = onlineConfigs.scan(k -> k.startsWith(onlinePrefix(region))).stream()
                .anyMatch(c -> name.equals(c.getOnlineEvaluationConfigName()));
        if (taken) {
            throw new AwsException("ConflictException",
                    "An online evaluation configuration named " + name + " already exists.", 409);
        }

        Instant now = Instant.now(clock);
        OnlineEvaluationConfig config = new OnlineEvaluationConfig();
        config.setOnlineEvaluationConfigId(name + "-" + random(10));
        config.setOnlineEvaluationConfigName(name);
        config.setDescription(description);
        config.setRule(req.get("rule").deepCopy());
        config.setDataSourceConfig(req.get("dataSourceConfig").deepCopy());
        config.setEvaluators(copyOrNull(req, "evaluators"));
        config.setInsights(copyOrNull(req, "insights"));
        config.setClusteringConfig(copyOrNull(req, "clusteringConfig"));
        config.setOutputConfig(copyOrNull(req, "outputConfig"));
        config.setEvaluationExecutionRoleArn(role);
        config.setStatus(STATUS_ACTIVE);
        config.setExecutionStatus(req.get("enableOnCreate").asBoolean() ? EXECUTION_ENABLED : EXECUTION_DISABLED);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        config.setClientToken(clientToken);
        Map<String, String> tags = BedrockAgentCoreControllerSupport.stringMap(req.get("tags"));
        if (tags != null) {
            config.getTags().putAll(tags);
        }
        onlineConfigs.put(onlineKey(region, config.getOnlineEvaluationConfigId()), config);
        return config;
    }

    public OnlineEvaluationConfig getOnlineConfig(String id, String region) {
        requireOnlineConfigId(id);
        return onlineConfigs.get(onlineKey(region, id)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Online evaluation configuration not found: " + id, 404));
    }

    public OnlineEvaluationConfig updateOnlineConfig(String id, JsonNode req, String region) {
        requireOnlineConfigId(id);
        BedrockAgentCoreEvaluationValidation.clientToken(req);
        String description = BedrockAgentCoreEvaluationValidation.string(req, "description", "", false, 1, 200, null);
        if (has(req, "rule")) {
            validateRule(req.get("rule"));
        }
        if (has(req, "dataSourceConfig")) {
            validateControlDataSource(req.get("dataSourceConfig"));
        }
        validateEvaluatorReferences(req, region);
        BedrockAgentCoreEvaluationValidation.insights(req, "", 10);
        validateClusteringConfig(req);
        validateOutputConfig(req);
        String role = BedrockAgentCoreEvaluationValidation.string(req, "evaluationExecutionRoleArn", "", false,
                1, 2048, ROLE_ARN);
        String execution = BedrockAgentCoreEvaluationValidation.enumValue(req, "executionStatus", "", false,
                Set.of(EXECUTION_ENABLED, EXECUTION_DISABLED));

        OnlineEvaluationConfig config = getOnlineConfig(id, region);
        if (description != null) {
            config.setDescription(description);
        }
        if (has(req, "rule")) {
            config.setRule(req.get("rule").deepCopy());
        }
        if (has(req, "dataSourceConfig")) {
            config.setDataSourceConfig(req.get("dataSourceConfig").deepCopy());
        }
        if (has(req, "evaluators")) {
            config.setEvaluators(req.get("evaluators").deepCopy());
        }
        if (has(req, "insights")) {
            config.setInsights(req.get("insights").deepCopy());
        }
        if (has(req, "clusteringConfig")) {
            config.setClusteringConfig(req.get("clusteringConfig").deepCopy());
        }
        if (has(req, "outputConfig")) {
            config.setOutputConfig(req.get("outputConfig").deepCopy());
        }
        if (role != null) {
            config.setEvaluationExecutionRoleArn(role);
        }
        if (execution != null) {
            config.setExecutionStatus(execution);
        }
        config.setStatus(STATUS_ACTIVE);
        config.setUpdatedAt(Instant.now(clock));
        onlineConfigs.put(onlineKey(region, id), config);
        return config;
    }

    public OnlineEvaluationConfig deleteOnlineConfig(String id, String region) {
        OnlineEvaluationConfig config = getOnlineConfig(id, region);
        onlineConfigs.delete(onlineKey(region, id));
        config.setStatus(STATUS_DELETING);
        return config;
    }

    public PaginatedResult<OnlineEvaluationConfig> listOnlineConfigs(Integer maxResults, String nextToken,
                                                                      String region) {
        List<OnlineEvaluationConfig> all = onlineConfigs.scan(k -> k.startsWith(onlinePrefix(region)));
        return Pagination.paginate(all, OnlineEvaluationConfig::getOnlineEvaluationConfigId, maxResults, nextToken,
                MAX_PAGE, "ValidationException");
    }

    /** Looks a configuration up by its ARN, for a batch evaluation that reads from one. */
    public Optional<OnlineEvaluationConfig> findOnlineConfigByArn(String arn, String region) {
        String marker = ":online-evaluation-config/";
        int at = arn == null ? -1 : arn.indexOf(marker);
        if (at < 0 || !arn.equals(onlineConfigArnFor(arn.substring(at + marker.length()), region))) {
            return Optional.empty();
        }
        return onlineConfigs.get(onlineKey(region, arn.substring(at + marker.length())));
    }

    public String onlineConfigArn(OnlineEvaluationConfig config, String region) {
        return onlineConfigArnFor(config.getOnlineEvaluationConfigId(), region);
    }

    private String onlineConfigArnFor(String id, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "online-evaluation-config/" + id);
    }

    private static void validateRule(JsonNode rule) {
        String p = "rule.";
        if (!rule.isObject()) {
            throw invalid("rule must be an object.");
        }
        JsonNode sampling = BedrockAgentCoreEvaluationValidation.requireObject(rule, "samplingConfig", p);
        BedrockAgentCoreEvaluationValidation.number(sampling, "samplingPercentage", p + "samplingConfig.", true,
                0.01, 100.0, false);
        List<JsonNode> filters = BedrockAgentCoreEvaluationValidation.list(rule, "filters", p, false, 0, 5);
        for (int i = 0; i < filters.size(); i++) {
            String fp = p + "filters[" + i + "].";
            JsonNode filter = filters.get(i);
            BedrockAgentCoreEvaluationValidation.string(filter, "key", fp, true, 1, 256,
                    BedrockAgentCoreEvaluationValidation.SERVICE_NAME);
            BedrockAgentCoreEvaluationValidation.enumValue(filter, "operator", fp, true, Set.of("Equals", "NotEquals",
                    "GreaterThan", "LessThan", "GreaterThanOrEqual", "LessThanOrEqual", "Contains", "NotContains"));
            JsonNode value = BedrockAgentCoreEvaluationValidation.requireMember(filter, "value", fp);
            String kind = BedrockAgentCoreEvaluationValidation.union(value, fp + "value",
                    Set.of("stringValue", "doubleValue", "booleanValue"));
            switch (kind) {
                case "stringValue" -> BedrockAgentCoreEvaluationValidation.string(value, kind, fp + "value.", true,
                        1, 1024, null);
                case "doubleValue" -> BedrockAgentCoreEvaluationValidation.number(value, kind, fp + "value.", true,
                        null, null, false);
                default -> BedrockAgentCoreEvaluationValidation.bool(value, kind, fp + "value.", true);
            }
        }
        JsonNode session = BedrockAgentCoreEvaluationValidation.optionalObject(rule, "sessionConfig", p);
        if (session != null) {
            BedrockAgentCoreEvaluationValidation.number(session, "sessionTimeoutMinutes", p + "sessionConfig.", true,
                    1.0, 1440.0, true);
        }
    }

    /** The control plane's {@code DataSourceConfig}: CloudWatch Logs only. */
    private static void validateControlDataSource(JsonNode source) {
        BedrockAgentCoreEvaluationValidation.union(source, "dataSourceConfig", Set.of("cloudWatchLogs"));
        validateCloudWatchLogsSource(source.get("cloudWatchLogs"), "dataSourceConfig.cloudWatchLogs.");
    }

    /** {@code CloudWatchLogsInputConfig} and the data plane's {@code CloudWatchLogsSource}. */
    public static void validateCloudWatchLogsSource(JsonNode source, String path) {
        if (!source.isObject()) {
            throw invalid(path + " must be an object.");
        }
        BedrockAgentCoreEvaluationValidation.stringList(source, "serviceNames", path, true, 1, 1, 1, 256,
                SERVICE_NAME);
        BedrockAgentCoreEvaluationValidation.stringList(source, "logGroupNames", path, false, 0, 10, -1, -1,
                LOG_GROUP_NAME);
        BedrockAgentCoreEvaluationValidation.stringList(source, "logGroupNamePrefixes", path, false, 1, 5, 1, 512,
                LOG_GROUP_NAME);
    }

    private void validateEvaluatorReferences(JsonNode req, String region) {
        List<JsonNode> refs = BedrockAgentCoreEvaluationValidation.list(req, "evaluators", "", false, 0, 25);
        for (int i = 0; i < refs.size(); i++) {
            JsonNode ref = refs.get(i);
            String p = "evaluators[" + i + "]";
            BedrockAgentCoreEvaluationValidation.union(ref, p, Set.of("evaluatorId"));
            String id = BedrockAgentCoreEvaluationValidation.string(ref, "evaluatorId", p + ".", true, 1, 111,
                    EVALUATOR_ID);
            if (findEvaluator(id, region).isEmpty()) {
                throw invalid("Evaluator not found: " + id);
            }
        }
    }

    private static void validateClusteringConfig(JsonNode req) {
        JsonNode clustering = BedrockAgentCoreEvaluationValidation.optionalObject(req, "clusteringConfig", "");
        if (clustering == null) {
            return;
        }
        List<JsonNode> frequencies = BedrockAgentCoreEvaluationValidation.list(clustering, "frequencies",
                "clusteringConfig.", true, 0, 3);
        for (JsonNode frequency : frequencies) {
            if (!Set.of("DAILY", "WEEKLY", "MONTHLY").contains(frequency.asText())) {
                throw invalid("clusteringConfig.frequencies must be DAILY, WEEKLY or MONTHLY.");
            }
        }
    }

    /** The control plane's {@code OutputConfig}, where {@code cloudWatchConfig} is required. */
    private static void validateOutputConfig(JsonNode req) {
        JsonNode output = BedrockAgentCoreEvaluationValidation.optionalObject(req, "outputConfig", "");
        if (output == null) {
            return;
        }
        validateCloudWatchOutput(BedrockAgentCoreEvaluationValidation.requireObject(output, "cloudWatchConfig",
                "outputConfig."), "outputConfig.cloudWatchConfig.");
    }

    /** {@code CloudWatchOutputConfig}, shared by both planes. */
    public static void validateCloudWatchOutput(JsonNode cloudWatch, String path) {
        BedrockAgentCoreEvaluationValidation.string(cloudWatch, "logGroupName", path, false, -1, -1,
                OPTIONAL_LOG_GROUP_NAME);
        BedrockAgentCoreEvaluationValidation.string(cloudWatch, "logStreamName", path, false, -1, -1,
                BedrockAgentCoreEvaluationValidation.LOG_STREAM_NAME);
        BedrockAgentCoreEvaluationValidation.string(cloudWatch, "metricsNamespace", path, false, 1, 255,
                METRICS_NAMESPACE);
        BedrockAgentCoreEvaluationValidation.enumValue(cloudWatch, "resultDestination", path, false,
                RESULT_DESTINATIONS);
    }

    private static List<String> referencedIds(JsonNode refs) {
        List<String> ids = new ArrayList<>();
        if (refs != null && refs.isArray()) {
            refs.forEach(ref -> {
                if (ref.hasNonNull("evaluatorId")) {
                    ids.add(ref.get("evaluatorId").asText());
                }
            });
        }
        return ids;
    }

    private static JsonNode copyOrNull(JsonNode req, String field) {
        return has(req, field) ? req.get(field).deepCopy() : null;
    }

    private static void requireEvaluatorId(String id) {
        if (id == null || id.isEmpty() || id.length() > 111 || !EVALUATOR_ID.matcher(id).matches()) {
            throw invalid("evaluatorId must match the pattern " + EVALUATOR_ID.pattern() + ".");
        }
    }

    private static void requireOnlineConfigId(String id) {
        if (id == null || !RESOURCE_ID.matcher(id).matches()) {
            throw invalid("onlineEvaluationConfigId must match the pattern " + RESOURCE_ID.pattern() + ".");
        }
    }

    // ── Tagging ──

    public Map<String, String> getTagsByArn(String region, String arn) {
        return new HashMap<>(switch (taggable(arn)) {
            case "evaluator" -> customByArn(region, arn).getTags();
            default -> onlineByArn(region, arn).getTags();
        });
    }

    public void tagByArn(String region, String arn, Map<String, String> tags) {
        if ("evaluator".equals(taggable(arn))) {
            Evaluator evaluator = customByArn(region, arn);
            evaluator.getTags().putAll(tags);
            evaluators.put(evaluatorKey(region, evaluator.getEvaluatorId()), evaluator);
        } else {
            OnlineEvaluationConfig config = onlineByArn(region, arn);
            config.getTags().putAll(tags);
            onlineConfigs.put(onlineKey(region, config.getOnlineEvaluationConfigId()), config);
        }
    }

    public void untagByArn(String region, String arn, List<String> keys) {
        if ("evaluator".equals(taggable(arn))) {
            Evaluator evaluator = customByArn(region, arn);
            keys.forEach(evaluator.getTags()::remove);
            evaluators.put(evaluatorKey(region, evaluator.getEvaluatorId()), evaluator);
        } else {
            OnlineEvaluationConfig config = onlineByArn(region, arn);
            keys.forEach(config.getTags()::remove);
            onlineConfigs.put(onlineKey(region, config.getOnlineEvaluationConfigId()), config);
        }
    }

    private static String taggable(String arn) {
        String resource = arn.split(":", 6)[5];
        return resource.substring(0, resource.indexOf('/'));
    }

    private Evaluator customByArn(String region, String arn) {
        String id = arn.split(":", 6)[5].substring("evaluator/".length());
        return evaluators.get(evaluatorKey(region, id)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Evaluator not found: " + id, 404));
    }

    private OnlineEvaluationConfig onlineByArn(String region, String arn) {
        String id = arn.split(":", 6)[5].substring("online-evaluation-config/".length());
        return onlineConfigs.get(onlineKey(region, id)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Online evaluation configuration not found: " + id, 404));
    }

    // ── Helpers ──

    /** The {@code EvaluatorSummary} shape, shared by ListEvaluators and GetEvaluator. */
    public ObjectNode summary(Evaluator evaluator, String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("evaluatorArn", evaluatorArn(evaluator, region));
        node.put("evaluatorId", evaluator.getEvaluatorId());
        node.put("evaluatorName", evaluator.getEvaluatorName());
        if (evaluator.getDescription() != null) {
            node.put("description", evaluator.getDescription());
        }
        node.put("evaluatorType", evaluator.getEvaluatorType());
        node.put("provider", evaluator.getProvider());
        node.put("level", evaluator.getLevel());
        node.put("status", evaluator.getStatus());
        node.put("createdAt", evaluator.getCreatedAt().getEpochSecond());
        node.put("updatedAt", evaluator.getUpdatedAt().getEpochSecond());
        if (isCustom(evaluator)) {
            node.put("lockedForModification", isLocked(evaluator, region));
        }
        if (evaluator.getKmsKeyArn() != null) {
            node.put("kmsKeyArn", evaluator.getKmsKeyArn());
        }
        return node;
    }

    private static String random(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALNUM.charAt(ThreadLocalRandom.current().nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    private static String evaluatorKey(String region, String id) {
        return evaluatorPrefix(region) + id;
    }

    private static String evaluatorPrefix(String region) {
        return "evaluator:" + region + ":";
    }

    private static String onlineKey(String region, String id) {
        return onlinePrefix(region) + id;
    }

    private static String onlinePrefix(String region) {
        return "online-evaluation-config:" + region + ":";
    }
}
