package io.github.hectorvent.floci.services.batch;

import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.V1Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for AWS Batch.
 *
 * <p>ARN formats: {@code arn:aws:batch:<region>:<account>:compute-environment/<name>},
 * {@code .../job-queue/<name>}, {@code .../job-definition/<name>:<revision>} and
 * {@code .../job/<jobId>}. Batch serves its tag endpoints on {@code /v1/tags/{resourceArn}},
 * the same path AppSync uses, with the default lowercase {@code tags} map body and
 * {@code tagKeys} query parameter.
 */
@ApplicationScoped
@V1Tags
public class BatchTagHandler implements TagHandler {

    private final BatchService service;

    @Inject
    public BatchTagHandler(BatchService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "batch";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.listTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(arn, tagKeys);
    }
}
