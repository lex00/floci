package io.github.hectorvent.floci.services.location;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Location's tag endpoints on the shared {@code /tags/{ResourceArn}} path. The body key is
 * {@code Tags}, a map, and both writes answer 200.
 */
@ApplicationScoped
public class LocationTagHandler implements TagHandler {

    private final LocationService service;

    @Inject
    public LocationTagHandler(LocationService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "geo";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
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
