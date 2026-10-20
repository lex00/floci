package io.github.hectorvent.floci.services.bedrock;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockModelCatalogTest {

    private final RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

    private BedrockModelCatalog catalog(String models, String extra) {
        return new BedrockModelCatalog(regionResolver, Optional.ofNullable(models), Optional.ofNullable(extra));
    }

    @Test
    void listFiltersByProviderModalityInferenceTypeAndCustomization() {
        BedrockModelCatalog catalog = catalog(null, null);

        List<BedrockModelCatalog.FoundationModel> anthropic =
                catalog.listFoundationModels("Anthropic", null, null, null);
        assertFalse(anthropic.isEmpty());
        assertTrue(anthropic.stream().allMatch(m -> m.modelId().startsWith("anthropic.")));
        assertEquals(anthropic, catalog.listFoundationModels("anthropic", null, null, null));

        assertTrue(catalog.listFoundationModels(null, "EMBEDDING", null, null).stream()
                .allMatch(m -> m.outputModalities().contains("EMBEDDING")));
        assertTrue(catalog.listFoundationModels(null, null, "PROVISIONED", null).stream()
                .allMatch(m -> m.inferenceTypesSupported().contains("PROVISIONED")));
        assertTrue(catalog.listFoundationModels(null, null, null, "CONTINUED_PRE_TRAINING").stream()
                .allMatch(m -> m.customizationsSupported().contains("CONTINUED_PRE_TRAINING")));
        assertTrue(catalog.listFoundationModels("nobody", null, null, null).isEmpty());
    }

    @Test
    void getFoundationModelAcceptsIdOrArn() {
        BedrockModelCatalog catalog = catalog(null, null);
        String arn = catalog.foundationModelArn("amazon.nova-lite-v1:0", "us-east-1");

        assertEquals("arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0", arn);
        assertEquals("amazon.nova-lite-v1:0", catalog.getFoundationModel(arn).modelId());
        assertEquals("Amazon", catalog.getFoundationModel("amazon.nova-lite-v1:0").providerName());
        AwsException missing = assertThrows(AwsException.class, () -> catalog.getFoundationModel("x.y"));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void configuredModelsReplaceTheCatalogAndExtraModelsAppend() {
        BedrockModelCatalog catalog = catalog("amazon.nova-lite-v1:0, qwen.qwen3-32b-v1:0", "deepseek.r1-v1:0");

        List<BedrockModelCatalog.FoundationModel> models = catalog.listFoundationModels(null, null, null, null);
        assertEquals(List.of("amazon.nova-lite-v1:0", "qwen.qwen3-32b-v1:0", "deepseek.r1-v1:0"),
                models.stream().map(BedrockModelCatalog.FoundationModel::modelId).toList());
        assertEquals("Nova Lite", models.get(0).modelName());
        assertEquals("Qwen", models.get(1).providerName());
        assertEquals(List.of("TEXT"), models.get(1).outputModalities());
    }

    @Test
    void systemProfilesFollowTheRegionGeography() {
        BedrockModelCatalog catalog = catalog(null, null);

        List<BedrockModelCatalog.InferenceProfile> us = catalog.systemInferenceProfiles("us-west-2");
        BedrockModelCatalog.InferenceProfile haiku = us.stream()
                .filter(p -> p.inferenceProfileId().equals("us.anthropic.claude-3-haiku-20240307-v1:0"))
                .findFirst().orElseThrow();
        assertEquals("arn:aws:bedrock:us-west-2:000000000000:inference-profile/"
                + "us.anthropic.claude-3-haiku-20240307-v1:0", haiku.inferenceProfileArn());
        assertEquals("SYSTEM_DEFINED", haiku.type());
        assertTrue(haiku.modelArns().contains(
                "arn:aws:bedrock:us-east-2::foundation-model/anthropic.claude-3-haiku-20240307-v1:0"));

        assertTrue(catalog.systemInferenceProfiles("eu-west-1").stream()
                .allMatch(p -> p.inferenceProfileId().startsWith("eu.")));
        assertTrue(catalog.systemInferenceProfiles("ap-southeast-2").stream()
                .allMatch(p -> p.inferenceProfileId().startsWith("apac.")));
        assertTrue(catalog.systemInferenceProfiles("sa-east-1").isEmpty());
        assertNotNull(catalog.findSystemInferenceProfile(haiku.inferenceProfileArn(), "us-west-2"));
        assertNull(catalog.findSystemInferenceProfile("eu.anthropic.claude-3-haiku-20240307-v1:0", "us-west-2"));
    }
}
