package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Enforces the account's effective Organizations tag policy on tag writes, when
 * {@code floci.services.organizations.tag-policy-enforcement-enabled} is on. A policy tag applies
 * when its {@code enforced_for} names the resource type or the service's {@code ALL_SUPPORTED}.
 * It then refuses a value outside {@code tag_value} and a key whose capitalization differs from
 * {@code tag_key}.
 */
@ApplicationScoped
public class TagPolicyEnforcer {

    private static final Logger LOG = Logger.getLogger(TagPolicyEnforcer.class);

    private final boolean enabled;
    private final OrganizationsService organizations;
    private final ObjectMapper objectMapper;

    @Inject
    public TagPolicyEnforcer(EmulatorConfig config, OrganizationsService organizations, ObjectMapper objectMapper) {
        this(config.services().organizations().tagPolicyEnforcementEnabled(), organizations, objectMapper);
    }

    TagPolicyEnforcer(boolean enabled, OrganizationsService organizations, ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.organizations = organizations;
        this.objectMapper = objectMapper;
    }

    /**
     * Refuses {@code tags} on a resource of {@code resourceType} (such as {@code ec2:instance})
     * that the account's effective tag policy does not allow, with error {@code errorCode}.
     */
    public void enforce(String accountId, String resourceType, Map<String, String> tags, String errorCode) {
        if (!enabled || tags == null || tags.isEmpty()) {
            return;
        }
        JsonNode policyTags = effectivePolicyTags(accountId);
        if (policyTags == null) {
            return;
        }
        Iterator<JsonNode> rules = policyTags.elements();
        while (rules.hasNext()) {
            JsonNode rule = rules.next();
            String policyKey = assigned(rule.get("tag_key")).stream().findFirst().orElse(null);
            if (policyKey == null || !enforcedFor(rule, resourceType)) {
                continue;
            }
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                if (!tag.getKey().equalsIgnoreCase(policyKey)) {
                    continue;
                }
                if (!tag.getKey().equals(policyKey)) {
                    throw new AwsException(errorCode, "The tag policy does not allow the specified "
                            + "capitalization for the following tag key: '" + tag.getKey() + "'.", 400);
                }
                List<String> allowed = assigned(rule.get("tag_value"));
                if (!allowed.isEmpty() && allowed.stream().noneMatch(pattern -> matches(pattern, tag.getValue()))) {
                    throw new AwsException(errorCode, "The tag policy does not allow the specified value "
                            + "for the following tag key: '" + tag.getKey() + "'.", 400);
                }
            }
        }
    }

    private JsonNode effectivePolicyTags(String accountId) {
        try {
            OrganizationsService.EffectivePolicy policy =
                    organizations.describeEffectivePolicy(accountId, "TAG_POLICY", accountId);
            JsonNode tags = objectMapper.readTree(policy.policyContent()).get("tags");
            return tags != null && tags.isObject() ? tags : null;
        } catch (AwsException e) {
            LOG.debugv("No effective tag policy for account {0}: {1}", accountId, e.getMessage());
            return null;
        } catch (Exception e) {
            LOG.warnv("Effective tag policy for account {0} could not be read: {1}", accountId, e.getMessage());
            return null;
        }
    }

    private static boolean enforcedFor(JsonNode rule, String resourceType) {
        String service = resourceType.substring(0, resourceType.indexOf(':') + 1);
        return assigned(rule.get("enforced_for")).stream().anyMatch(type ->
                type.equalsIgnoreCase(resourceType) || type.equalsIgnoreCase(service + "ALL_SUPPORTED"));
    }

    /** The values a policy member assigns, whether written with {@code @@assign} or as a resolved value. */
    private static List<String> assigned(JsonNode member) {
        List<String> values = new ArrayList<>();
        if (member == null) {
            return values;
        }
        JsonNode value = member.has("@@assign") ? member.get("@@assign") : member;
        if (value.isArray()) {
            value.forEach(element -> values.add(element.asText()));
        } else if (value.isValueNode()) {
            values.add(value.asText());
        }
        return values;
    }

    /** Tag policy values allow one trailing {@code *} wildcard. */
    private static boolean matches(String pattern, String value) {
        if (value == null) {
            return false;
        }
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    }
}
