package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the IAM condition context (a key → value map handed to
 * {@link io.github.hectorvent.floci.services.iam.IamPolicyEvaluator}) for a
 * request, so that {@code Condition} blocks in policy documents can be
 * evaluated.
 *
 * <p>Coverage is deliberately narrow and grows request by request; an
 * unhandled (service, action) pair returns {@code null}, which the evaluator
 * treats as "no context available" (any condition requiring a missing key
 * fails to match, per AWS's own semantics).
 *
 * <p>Two condition keys are generic across services and populated the same
 * way everywhere they are supported below:
 * <ul>
 *   <li>{@code aws:RequestTag/<key>} — a tag the caller is asking to attach,
 *       read from the request body/params before the operation is applied.</li>
 *   <li>{@code aws:ResourceTag/<key>} — a tag already on the target resource,
 *       read from current state. Only supported where the action names a
 *       single existing resource; a request naming several (e.g.
 *       {@code TerminateInstances} on more than one instance) is evaluated
 *       against the FIRST resource id only — AWS evaluates the condition
 *       once per resource in the request, which this emulator does not model
 *       (the policy evaluator takes one resource ARN per call). Document this
 *       gap rather than silently mis-scoping multi-resource calls.</li>
 * </ul>
 */
@ApplicationScoped
public class IamConditionContextResolver {

    private static final Logger LOG = Logger.getLogger(IamConditionContextResolver.class);

    private final EmulatorConfig config;
    private final RequestContext requestContext;
    private final Ec2Service ec2Service;
    private final S3Service s3Service;

    @Inject
    public IamConditionContextResolver(EmulatorConfig config,
                                       RequestContext requestContext,
                                       Ec2Service ec2Service,
                                       S3Service s3Service) {
        this.config = config;
        this.requestContext = requestContext;
        this.ec2Service = ec2Service;
        this.s3Service = s3Service;
    }

    public Map<String, String> resolve(String credentialScope, String action, ContainerRequestContext ctx) {
        return switch (credentialScope) {
            case "s3" -> s3ConditionContext(action, ctx);
            case "ec2" -> ec2ConditionContext(action, ctx);
            default -> null;
        };
    }

    // ─── S3 ──────────────────────────────────────────────────────────────────

    private Map<String, String> s3ConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "s3:ListBucket" -> s3BucketListConditionContext(ctx.getUriInfo().getQueryParameters());
            case "s3:PutBucketTagging" -> s3PutBucketTaggingConditionContext(ctx);
            case "s3:GetBucketTagging", "s3:DeleteBucketTagging", "s3:DeleteBucket" ->
                    s3ResourceTagConditionContext(ctx);
            default -> null;
        };
    }

    Map<String, String> s3BucketListConditionContext(MultivaluedMap<String, String> queryParameters) {
        Map<String, String> conditions = new LinkedHashMap<>();
        addQueryCondition(conditions, "s3:prefix", queryParameters, "prefix");
        addQueryCondition(conditions, "s3:delimiter", queryParameters, "delimiter");
        addQueryCondition(conditions, "s3:max-keys", queryParameters, "max-keys");
        return conditions.isEmpty() ? null : conditions;
    }

    /** {@code aws:RequestTag/<key>} from the {@code <Tagging><TagSet>} XML body of PutBucketTagging. */
    private Map<String, String> s3PutBucketTaggingConditionContext(ContainerRequestContext ctx) {
        byte[] body = bufferEntity(ctx);
        if (body == null || body.length == 0) {
            return null;
        }
        String xml = new String(body, StandardCharsets.UTF_8);
        Map<String, String> tags = XmlParser.extractPairs(xml, "Tag", "Key", "Value");
        if (tags.isEmpty()) {
            return null;
        }
        Map<String, String> conditions = new LinkedHashMap<>();
        tags.forEach((key, value) -> conditions.put("aws:RequestTag/" + key, value));
        return conditions;
    }

    /** {@code aws:ResourceTag/<key>} from the target bucket's current tags. */
    private Map<String, String> s3ResourceTagConditionContext(ContainerRequestContext ctx) {
        String bucket = extractS3BucketName(ctx.getUriInfo().getPath());
        if (bucket == null || s3Service == null) {
            return null;
        }
        Map<String, String> existing;
        try {
            existing = s3Service.getBucketTagging(bucket);
        } catch (RuntimeException e) {
            // Bucket doesn't exist yet (or any other lookup failure) — no resource tags to offer.
            LOG.debugv(e, "Could not read bucket tags for condition context: {0}", bucket);
            return null;
        }
        if (existing == null || existing.isEmpty()) {
            return null;
        }
        Map<String, String> conditions = new LinkedHashMap<>();
        existing.forEach((key, value) -> conditions.put("aws:ResourceTag/" + key, value));
        return conditions;
    }

    private static String extractS3BucketName(String path) {
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) {
            return null;
        }
        int slash = stripped.indexOf('/');
        return slash < 0 ? stripped : stripped.substring(0, slash);
    }

    private static void addQueryCondition(Map<String, String> conditions, String conditionKey,
                                          MultivaluedMap<String, String> queryParameters, String queryParameter) {
        String value = queryParameters.getFirst(queryParameter);
        if (value != null) {
            conditions.put(conditionKey, value);
        }
    }

    // ─── EC2 ─────────────────────────────────────────────────────────────────

    private Map<String, String> ec2ConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "ec2:RunInstances"       -> ec2RunInstancesConditionContext(ctx);
            case "ec2:CreateTags"         -> ec2CreateTagsConditionContext(ctx);
            case "ec2:DeleteTags"         -> ec2ResourceTagConditionContext(ctx, "ResourceId");
            case "ec2:TerminateInstances" -> ec2ResourceTagConditionContext(ctx, "InstanceId");
            case "ec2:DescribeInstances"  -> ec2ResourceTagConditionContext(ctx, "InstanceId");
            default -> null;
        };
    }

    /** {@code aws:RequestTag/<key>} from every {@code TagSpecification.N.Tag.M} pair. The launched
     *  instance doesn't exist yet, so there is no {@code aws:ResourceTag} to offer here. */
    private Map<String, String> ec2RunInstancesConditionContext(ContainerRequestContext ctx) {
        Map<String, String> form = readFormParams(ctx);
        Map<String, String> conditions = new LinkedHashMap<>();
        for (int specIndex = 1; form.containsKey("TagSpecification." + specIndex + ".ResourceType"); specIndex++) {
            addIndexedTagPairs(conditions, form, "TagSpecification." + specIndex + ".Tag.");
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /** CreateTags both requests new tags and targets an existing resource, so both keys apply:
     *  {@code aws:RequestTag/<key>} from the {@code Tag.N} pairs being requested, and
     *  {@code aws:ResourceTag/<key>} from the FIRST {@code ResourceId.N}'s current tags
     *  (i.e. its tags before this call is applied). */
    private Map<String, String> ec2CreateTagsConditionContext(ContainerRequestContext ctx) {
        Map<String, String> form = readFormParams(ctx);
        Map<String, String> conditions = new LinkedHashMap<>();
        addIndexedTagPairs(conditions, form, "Tag.");
        addResourceTagsFromForm(conditions, form, "ResourceId.1");
        return conditions.isEmpty() ? null : conditions;
    }

    /** {@code aws:ResourceTag/<key>} from the first named resource's current tags. */
    private Map<String, String> ec2ResourceTagConditionContext(ContainerRequestContext ctx, String idParamPrefix) {
        Map<String, String> form = readFormParams(ctx);
        Map<String, String> conditions = new LinkedHashMap<>();
        addResourceTagsFromForm(conditions, form, idParamPrefix + ".1");
        return conditions.isEmpty() ? null : conditions;
    }

    private void addIndexedTagPairs(Map<String, String> conditions, Map<String, String> form, String prefix) {
        for (int i = 1; form.containsKey(prefix + i + ".Key"); i++) {
            String key = form.get(prefix + i + ".Key");
            String value = form.get(prefix + i + ".Value");
            conditions.put("aws:RequestTag/" + key, value == null ? "" : value);
        }
    }

    private void addResourceTagsFromForm(Map<String, String> conditions, Map<String, String> form, String idParam) {
        String resourceId = form.get(idParam);
        if (resourceId == null || ec2Service == null) {
            return;
        }
        String region = requestContext != null && requestContext.getRegion() != null
                ? requestContext.getRegion()
                : (config != null ? config.defaultRegion() : "us-east-1");
        List<Tag> tags;
        try {
            tags = ec2Service.effectiveTags(region, resourceId);
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not read resource tags for condition context: {0}", resourceId);
            return;
        }
        for (Tag tag : tags) {
            conditions.put("aws:ResourceTag/" + tag.getKey(), tag.getValue());
        }
    }

    // ─── Shared body-reading helpers ────────────────────────────────────────
    //
    // Both mirror IamActionRegistry's own buffer-and-restore trick: the entity
    // stream can only be consumed once, so any peek here must put it back for
    // whatever reads the body next (the actual service handler).

    private static Map<String, String> readFormParams(ContainerRequestContext ctx) {
        byte[] body = bufferEntity(ctx);
        if (body == null || body.length == 0) {
            return Map.of();
        }
        Charset charset = resolveCharset(ctx.getMediaType());
        Map<String, String> params = new LinkedHashMap<>();
        String form = new String(body, charset);
        for (String pair : form.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(key, charset), URLDecoder.decode(value, charset));
        }
        return params;
    }

    /** Buffers the entity stream fully, restores it for downstream readers, and returns the bytes. */
    private static byte[] bufferEntity(ContainerRequestContext ctx) {
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            LOG.debugv(e, "Failed to buffer request body for IAM condition context resolution");
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        return body;
    }

    private static Charset resolveCharset(MediaType mt) {
        if (mt == null) {
            return StandardCharsets.UTF_8;
        }
        String name = mt.getParameters().get("charset");
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
