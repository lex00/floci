package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.location.LocationClient;
import software.amazon.awssdk.services.location.model.DescribeGeofenceCollectionResponse;
import software.amazon.awssdk.services.location.model.ResourceNotFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocationTest {

    @Test
    void geofenceCollectionLifecycleWithTags() {
        try (LocationClient location = TestFixtures.locationClient()) {
            String name = TestFixtures.uniqueName("fences");
            String arn = location.createGeofenceCollection(r -> r.collectionName(name)
                    .tags(Map.of("team", "ops"))).collectionArn();

            DescribeGeofenceCollectionResponse described =
                    location.describeGeofenceCollection(r -> r.collectionName(name));
            assertThat(described.collectionArn()).isEqualTo(arn);
            assertThat(described.createTime()).isNotNull();
            assertThat(location.listTagsForResource(r -> r.resourceArn(arn)).tags()).containsEntry("team", "ops");
            assertThat(location.listGeofenceCollections(r -> { }).entries())
                    .anyMatch(entry -> entry.collectionName().equals(name));

            location.deleteGeofenceCollection(r -> r.collectionName(name));
            assertThatThrownBy(() -> location.describeGeofenceCollection(r -> r.collectionName(name)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
