package io.github.hectorvent.floci.services.qbusiness;

import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.V1Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Q Business's tag endpoints on {@code /v1/tags/{resourceARN}}. The body holds a {@code tags}
 * list of lowercase {@code key} and {@code value} entries, and both writes answer 200.
 */
@ApplicationScoped
@V1Tags
public class QBusinessTagHandler implements TagHandler {

    private final QBusinessService service;

    @Inject
    public QBusinessTagHandler(QBusinessService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "qbusiness";
    }

    @Override
    public boolean tagsBodyIsList() {
        return true;
    }

    @Override
    public String tagEntryKeyName() {
        return "key";
    }

    @Override
    public String tagEntryValueName() {
        return "value";
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
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
