package io.github.hectorvent.floci.services.bedrock;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The foundation models and system-defined inference profiles Bedrock lists. AWS publishes no
 * enumeration of model ids in its service model, so this is a fixed catalog of ids the Bedrock
 * Runtime stub answers for and an OpenAI-compatible proxy can be mapped to.
 *
 * <p>{@code floci.services.bedrock.foundation-models} replaces the catalog with the listed ids, and
 * {@code floci.services.bedrock.extra-foundation-models} appends to it. A listed id the catalog
 * does not know is listed as a text model from the provider its id names.
 */
@ApplicationScoped
public class BedrockModelCatalog {

    public static final String SYSTEM_DEFINED = "SYSTEM_DEFINED";
    public static final String APPLICATION = "APPLICATION";

    /** When the system-defined profiles report they were created. */
    static final Instant SYSTEM_PROFILE_CREATED_AT = Instant.parse("2024-08-01T00:00:00Z");

    /** The id prefix of a cross-region profile, and the regions it routes to, per geography. */
    private static final Map<String, List<String>> GEOGRAPHIES = Map.of(
            "us", List.of("us-east-1", "us-east-2", "us-west-2"), // partition-literal: profile routing table
            "eu", List.of("eu-central-1", "eu-west-1", "eu-west-3"), // partition-literal: profile routing table
            "apac", List.of("ap-northeast-1", "ap-south-1", // partition-literal: profile routing table
                    "ap-southeast-1", "ap-southeast-2")); // partition-literal: profile routing table

    private static final List<String> TEXT = List.of("TEXT");
    private static final List<String> TEXT_IMAGE = List.of("TEXT", "IMAGE");
    private static final List<String> ON_DEMAND = List.of("ON_DEMAND");
    private static final List<String> ON_DEMAND_PROVISIONED = List.of("ON_DEMAND", "PROVISIONED");

    private static final List<FoundationModel> DEFAULT_MODELS = List.of(
            new FoundationModel("anthropic.claude-3-haiku-20240307-v1:0", "Claude 3 Haiku", "Anthropic",
                    TEXT_IMAGE, TEXT, true, List.of("FINE_TUNING"), ON_DEMAND_PROVISIONED, true),
            new FoundationModel("anthropic.claude-3-5-haiku-20241022-v1:0", "Claude 3.5 Haiku", "Anthropic",
                    TEXT, TEXT, true, List.of(), ON_DEMAND, true),
            new FoundationModel("anthropic.claude-3-5-sonnet-20240620-v1:0", "Claude 3.5 Sonnet", "Anthropic",
                    TEXT_IMAGE, TEXT, true, List.of(), ON_DEMAND_PROVISIONED, true),
            new FoundationModel("anthropic.claude-3-5-sonnet-20241022-v2:0", "Claude 3.5 Sonnet v2", "Anthropic",
                    TEXT_IMAGE, TEXT, true, List.of(), ON_DEMAND, true),
            new FoundationModel("anthropic.claude-3-7-sonnet-20250219-v1:0", "Claude 3.7 Sonnet", "Anthropic",
                    TEXT_IMAGE, TEXT, true, List.of(), ON_DEMAND, true),
            new FoundationModel("anthropic.claude-sonnet-4-20250514-v1:0", "Claude Sonnet 4", "Anthropic",
                    TEXT_IMAGE, TEXT, true, List.of(), ON_DEMAND, true),
            new FoundationModel("amazon.nova-micro-v1:0", "Nova Micro", "Amazon",
                    TEXT, TEXT, true, List.of("FINE_TUNING", "DISTILLATION"), ON_DEMAND, true),
            new FoundationModel("amazon.nova-lite-v1:0", "Nova Lite", "Amazon",
                    List.of("TEXT", "IMAGE"), TEXT, true, List.of("FINE_TUNING", "DISTILLATION"), ON_DEMAND, true),
            new FoundationModel("amazon.nova-pro-v1:0", "Nova Pro", "Amazon",
                    List.of("TEXT", "IMAGE"), TEXT, true, List.of("FINE_TUNING", "DISTILLATION"), ON_DEMAND, true),
            new FoundationModel("amazon.titan-text-express-v1", "Titan Text G1 - Express", "Amazon",
                    TEXT, TEXT, true, List.of("FINE_TUNING", "CONTINUED_PRE_TRAINING"), ON_DEMAND_PROVISIONED, false),
            new FoundationModel("amazon.titan-embed-text-v2:0", "Titan Text Embeddings V2", "Amazon",
                    TEXT, List.of("EMBEDDING"), false, List.of(), ON_DEMAND, false),
            new FoundationModel("amazon.titan-image-generator-v2:0", "Titan Image Generator G1 v2", "Amazon",
                    TEXT_IMAGE, List.of("IMAGE"), false, List.of("FINE_TUNING"), ON_DEMAND_PROVISIONED, false),
            new FoundationModel("meta.llama3-1-8b-instruct-v1:0", "Llama 3.1 8B Instruct", "Meta",
                    TEXT, TEXT, true, List.of("FINE_TUNING"), ON_DEMAND, true),
            new FoundationModel("meta.llama3-1-70b-instruct-v1:0", "Llama 3.1 70B Instruct", "Meta",
                    TEXT, TEXT, true, List.of("FINE_TUNING"), ON_DEMAND, true),
            new FoundationModel("mistral.mistral-large-2402-v1:0", "Mistral Large (24.02)", "Mistral AI",
                    TEXT, TEXT, true, List.of(), ON_DEMAND, false),
            new FoundationModel("cohere.embed-english-v3", "Embed English", "Cohere",
                    TEXT, List.of("EMBEDDING"), false, List.of(), ON_DEMAND_PROVISIONED, false));

    private final RegionResolver regionResolver;
    private final List<FoundationModel> models;

    @Inject
    public BedrockModelCatalog(EmulatorConfig config, RegionResolver regionResolver) {
        this(regionResolver, config.services().bedrock().foundationModels(),
                config.services().bedrock().extraFoundationModels());
    }

    BedrockModelCatalog(RegionResolver regionResolver, Optional<String> foundationModels,
                        Optional<String> extraFoundationModels) {
        this.regionResolver = regionResolver;
        Map<String, FoundationModel> byId = new LinkedHashMap<>();
        List<String> replacement = ids(foundationModels);
        if (replacement.isEmpty()) {
            DEFAULT_MODELS.forEach(model -> byId.put(model.modelId(), model));
        } else {
            replacement.forEach(id -> byId.put(id, known(id)));
        }
        ids(extraFoundationModels).forEach(id -> byId.putIfAbsent(id, known(id)));
        this.models = List.copyOf(byId.values());
    }

    /** {@code ListFoundationModels}: every filter that is given must match. */
    public List<FoundationModel> listFoundationModels(String byProvider, String byOutputModality,
                                                      String byInferenceType, String byCustomizationType) {
        return models.stream()
                .filter(model -> byProvider == null || byProvider.isBlank()
                        || model.providerName().equalsIgnoreCase(byProvider)
                        || model.provider().equalsIgnoreCase(byProvider))
                .filter(model -> byOutputModality == null || model.outputModalities().contains(byOutputModality))
                .filter(model -> byInferenceType == null || model.inferenceTypesSupported().contains(byInferenceType))
                .filter(model -> byCustomizationType == null
                        || model.customizationsSupported().contains(byCustomizationType))
                .toList();
    }

    /** {@code GetFoundationModel} by model id or foundation-model ARN. */
    public FoundationModel getFoundationModel(String modelIdentifier) {
        String modelId = modelIdentifier;
        int marker = modelId == null ? -1 : modelId.indexOf(":foundation-model/");
        if (marker >= 0) {
            modelId = modelId.substring(marker + ":foundation-model/".length());
        }
        String wanted = modelId;
        return models.stream()
                .filter(model -> model.modelId().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The model " + modelIdentifier + " does not exist.", 404));
    }

    public String foundationModelArn(String modelId, String region) {
        return regionResolver.buildArn("bedrock", region, "", "foundation-model/" + modelId);
    }

    /**
     * The system-defined cross-region profiles of the request region's geography, one per catalog
     * model that supports cross-region inference. A region outside the US, EU and Asia Pacific
     * geographies has none.
     */
    public List<InferenceProfile> systemInferenceProfiles(String region) {
        String geography = geographyOf(region);
        if (geography == null) {
            return List.of();
        }
        List<InferenceProfile> profiles = new ArrayList<>();
        for (FoundationModel model : models) {
            if (!model.crossRegion()) {
                continue;
            }
            String profileId = geography + "." + model.modelId();
            List<String> modelArns = GEOGRAPHIES.get(geography).stream()
                    .map(memberRegion -> foundationModelArn(model.modelId(), memberRegion))
                    .toList();
            profiles.add(new InferenceProfile(profileId,
                    geography.toUpperCase(Locale.ROOT) + " " + model.modelName(),
                    "Routes requests to " + model.modelName() + " in " + geography.toUpperCase(Locale.ROOT)
                            + " regions",
                    regionResolver.buildArn("bedrock", region, "inference-profile/" + profileId),
                    modelArns, SYSTEM_DEFINED, SYSTEM_PROFILE_CREATED_AT, SYSTEM_PROFILE_CREATED_AT));
        }
        return profiles;
    }

    /** A system-defined profile of the region by id or ARN, or {@code null}. */
    public InferenceProfile findSystemInferenceProfile(String identifier, String region) {
        String profileId = profileIdOf(identifier);
        return systemInferenceProfiles(region).stream()
                .filter(profile -> profile.inferenceProfileId().equals(profileId))
                .findFirst()
                .orElse(null);
    }

    /** The id an {@code InferenceProfileIdentifier} names, whether it is an id or an ARN. */
    static String profileIdOf(String identifier) {
        if (identifier == null) {
            return null;
        }
        int slash = identifier.startsWith("arn:") ? identifier.lastIndexOf('/') : -1;
        return slash >= 0 ? identifier.substring(slash + 1) : identifier;
    }

    static String geographyOf(String region) {
        if (region == null) {
            return null;
        }
        if (region.startsWith("us-") && !region.startsWith("us-gov-")) {
            return "us";
        }
        if (region.startsWith("eu-")) {
            return "eu";
        }
        if (region.startsWith("ap-")) {
            return "apac";
        }
        return null;
    }

    private static List<String> ids(Optional<String> configured) {
        List<String> ids = new ArrayList<>();
        for (String id : configured.orElse("").split(",")) {
            if (!id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    private static FoundationModel known(String modelId) {
        for (FoundationModel model : DEFAULT_MODELS) {
            if (model.modelId().equals(modelId)) {
                return model;
            }
        }
        int dot = modelId.indexOf('.');
        String provider = dot > 0 ? modelId.substring(0, dot) : modelId;
        String providerName = provider.isEmpty() ? provider
                : provider.substring(0, 1).toUpperCase(Locale.ROOT) + provider.substring(1);
        return new FoundationModel(modelId, modelId, providerName, TEXT, TEXT, true, List.of(), ON_DEMAND, false);
    }

    /**
     * One {@code FoundationModelSummary}. {@code crossRegion} says whether the model has
     * system-defined cross-region inference profiles.
     */
    public record FoundationModel(String modelId, String modelName, String providerName,
                                  List<String> inputModalities, List<String> outputModalities,
                                  boolean responseStreamingSupported, List<String> customizationsSupported,
                                  List<String> inferenceTypesSupported, boolean crossRegion) {

        /** The provider as its model ids spell it, such as {@code anthropic}. */
        public String provider() {
            int dot = modelId.indexOf('.');
            return dot > 0 ? modelId.substring(0, dot) : modelId;
        }
    }

    /** One inference profile, system-defined or application. */
    public record InferenceProfile(String inferenceProfileId, String inferenceProfileName, String description,
                                   String inferenceProfileArn, List<String> modelArns, String type,
                                   Instant createdAt, Instant updatedAt) {
    }
}
