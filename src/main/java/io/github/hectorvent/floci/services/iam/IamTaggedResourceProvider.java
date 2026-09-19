package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.resourcegroupstagging.TaggedResourceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes IAM's own tags to the Resource Groups Tagging API's read side.
 *
 * <p>IAM writes tags onto the resource ({@code CreatePolicy --tags}, {@code TagInstanceProfile},
 * {@code TagRole}), never into the tagging service's store, so without this the tag index is
 * empty for an account full of tagged IAM resources.
 *
 * <p>Roles are reported even though the index never serves them. The rule about which types AWS
 * indexes lives in one place,
 * {@code ResourceGroupsTaggingService.servedInRegion}, so that the exclusion can be exercised
 * against a role that genuinely carries tags rather than against one that was never offered.
 */
@ApplicationScoped
public class IamTaggedResourceProvider implements TaggedResourceProvider {

    private final IamService iamService;

    @Inject
    public IamTaggedResourceProvider(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Map<String, Map<String, String>> taggedResources() {
        Map<String, Map<String, String>> byArn = new LinkedHashMap<>();
        // Local scope only: AWS-managed policies are not the caller's resources and carry no tags.
        iamService.listPolicies("Local", null)
                .forEach(p -> put(byArn, p.getArn(), p.getTags()));
        iamService.listInstanceProfiles(null)
                .forEach(p -> put(byArn, p.getArn(), p.getTags()));
        iamService.listRoles(null)
                .forEach(r -> put(byArn, r.getArn(), r.getTags()));
        return byArn;
    }

    private void put(Map<String, Map<String, String>> byArn, String arn, Map<String, String> tags) {
        if (arn == null || tags == null || tags.isEmpty()) {
            return;
        }
        byArn.put(arn, Map.copyOf(tags));
    }
}
