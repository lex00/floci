package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kendra.KendraClient;
import software.amazon.awssdk.services.kendra.model.DescribeIndexResponse;
import software.amazon.awssdk.services.kendra.model.IndexEdition;
import software.amazon.awssdk.services.kendra.model.IndexStatus;
import software.amazon.awssdk.services.kendra.model.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KendraTest {

    @Test
    void indexLifecycle() {
        try (KendraClient kendra = TestFixtures.kendraClient()) {
            String id = kendra.createIndex(r -> r.name(TestFixtures.uniqueName("idx"))
                    .roleArn("arn:aws:iam::000000000000:role/kendra")
                    .edition(IndexEdition.DEVELOPER_EDITION)).id();

            DescribeIndexResponse index = kendra.describeIndex(r -> r.id(id));
            assertThat(index.status()).isEqualTo(IndexStatus.ACTIVE);
            assertThat(index.edition()).isEqualTo(IndexEdition.DEVELOPER_EDITION);
            assertThat(index.createdAt()).isNotNull();
            assertThat(kendra.listIndices(r -> { }).indexConfigurationSummaryItems())
                    .anyMatch(summary -> summary.id().equals(id));

            kendra.deleteIndex(r -> r.id(id));
            assertThatThrownBy(() -> kendra.describeIndex(r -> r.id(id)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
