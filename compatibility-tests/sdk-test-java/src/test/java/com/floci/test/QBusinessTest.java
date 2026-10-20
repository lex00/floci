package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.qbusiness.QBusinessClient;
import software.amazon.awssdk.services.qbusiness.model.ApplicationStatus;
import software.amazon.awssdk.services.qbusiness.model.CreateApplicationResponse;
import software.amazon.awssdk.services.qbusiness.model.GetApplicationResponse;
import software.amazon.awssdk.services.qbusiness.model.ResourceNotFoundException;
import software.amazon.awssdk.services.qbusiness.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QBusinessTest {

    @Test
    void applicationLifecycleWithTags() {
        try (QBusinessClient qbusiness = TestFixtures.qBusinessClient()) {
            CreateApplicationResponse created = qbusiness.createApplication(r -> r
                    .displayName(TestFixtures.uniqueName("app"))
                    .tags(Tag.builder().key("team").value("it").build()));

            GetApplicationResponse app = qbusiness.getApplication(r -> r.applicationId(created.applicationId()));
            assertThat(app.status()).isEqualTo(ApplicationStatus.ACTIVE);
            assertThat(app.applicationArn()).isEqualTo(created.applicationArn());
            assertThat(qbusiness.listTagsForResource(r -> r.resourceARN(created.applicationArn())).tags())
                    .extracting(Tag::key).containsExactly("team");

            qbusiness.deleteApplication(r -> r.applicationId(created.applicationId()));
            assertThatThrownBy(() -> qbusiness.getApplication(r -> r.applicationId(created.applicationId())))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
