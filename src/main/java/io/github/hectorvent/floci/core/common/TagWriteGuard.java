package io.github.hectorvent.floci.core.common;

import java.util.Map;

/**
 * Checks a tag write before it is applied, for the shared tag paths in core that cannot name the
 * Organizations service. {@code TagPolicyEnforcer} implements it with the account's tag policy.
 */
public interface TagWriteGuard {

    /**
     * Refuses {@code tags} on the resource {@code resourceArn} names, with {@code errorCode}, when
     * the account's policy does not allow them.
     */
    void checkArn(String accountId, String resourceArn, Map<String, String> tags, String errorCode);

    /**
     * The tag policy resource type of an ARN, such as {@code lambda:function} or
     * {@code s3:bucket}: the service, then the resource segment up to its first {@code /} or
     * {@code :}. SNS topics, SQS queues and S3 buckets have no type segment and get theirs here.
     */
    static String resourceTypeOf(String resourceArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        String resource = arn.resource();
        String type = switch (arn.service()) {
            case "sns" -> resource.contains(":") ? resource.substring(0, resource.indexOf(':')) : "topic";
            case "sqs" -> "queue";
            case "s3" -> resource.contains("/") ? "object" : "bucket";
            default -> {
                int end = resource.length();
                for (char separator : new char[] {'/', ':'}) {
                    int index = resource.indexOf(separator);
                    if (index >= 0 && index < end) {
                        end = index;
                    }
                }
                yield resource.substring(0, end);
            }
        };
        return arn.service() + ":" + type;
    }
}
