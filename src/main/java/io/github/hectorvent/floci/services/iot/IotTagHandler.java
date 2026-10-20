package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IotTagHandler implements TagHandler {

    private final IotService iotService;
    private final IotDomainConfigurationService domainConfigurationService;
    private final IotAuthorizerService authorizerService;
    private final IotBillingGroupService billingGroupService;

    @Inject
    public IotTagHandler(IotService iotService, IotDomainConfigurationService domainConfigurationService,
                         IotAuthorizerService authorizerService, IotBillingGroupService billingGroupService) {
        this.iotService = iotService;
        this.domainConfigurationService = domainConfigurationService;
        this.authorizerService = authorizerService;
        this.billingGroupService = billingGroupService;
    }

    @Override
    public String serviceKey() {
        return "iot";
    }

    @Override
    public boolean tagsBodyIsList() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        if (IotBillingGroupService.owns(arn)) {
            return billingGroupService.listTagsForResource(arn);
        }
        if (isAuthorizer(arn)) {
            return authorizerService.listTagsForResource(arn);
        }
        return isDomainConfiguration(arn)
                ? domainConfigurationService.listTagsForResource(arn)
                : iotService.listTagsForResource(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (IotBillingGroupService.owns(arn)) {
            billingGroupService.tagResource(arn, tags);
        } else if (isAuthorizer(arn)) {
            authorizerService.tagResource(arn, tags);
        } else if (isDomainConfiguration(arn)) {
            domainConfigurationService.tagResource(arn, tags);
        } else {
            iotService.tagResource(arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (IotBillingGroupService.owns(arn)) {
            billingGroupService.untagResource(arn, tagKeys);
        } else if (isAuthorizer(arn)) {
            authorizerService.untagResource(arn, tagKeys);
        } else if (isDomainConfiguration(arn)) {
            domainConfigurationService.untagResource(arn, tagKeys);
        } else {
            iotService.untagResource(arn, tagKeys);
        }
    }

    /**
     * Domain configurations, authorizers, billing groups and role aliases have their own services;
     * every other IoT resource is tagged through IotService.
     */
    private static boolean isDomainConfiguration(String arn) {
        return arn != null && arn.contains(":domainconfiguration/");
    }

    private static boolean isAuthorizer(String arn) {
        return AwsArnUtils.isArn(arn) && AwsArnUtils.parse(arn).resource().startsWith("authorizer/");
    }
}
