package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.services.datazone.model.DataZoneDomain;
import io.github.hectorvent.floci.services.datazone.model.DataZoneGlossary;
import io.github.hectorvent.floci.services.datazone.model.DataZoneGlossaryTerm;
import io.github.hectorvent.floci.services.datazone.model.DataZoneProject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Amazon DataZone REST JSON API: domains, projects, glossaries and glossary terms. */
@Path("/v2/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneController {

    private final DataZoneService service;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneController(DataZoneService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    // Domains

    @POST
    public Response createDomain(@Context HttpHeaders headers, JsonNode request) {
        JsonNode body = body(request);
        DataZoneDomain domain = service.createDomain(region(headers), text(body, "name"),
                text(body, "description"), text(body, "domainExecutionRole"), text(body, "serviceRole"),
                text(body, "kmsKeyIdentifier"), text(body, "domainVersion"), object(body, "singleSignOn"),
                stringMap(body.get("tags")));
        Map<String, Object> out = domainBody(domain);
        out.remove("createdAt");
        out.remove("lastUpdatedAt");
        return Response.status(201).entity(out).build();
    }

    @GET
    public Response listDomains(@Context HttpHeaders headers, @QueryParam("status") String status,
                                @QueryParam("maxResults") Integer maxResults,
                                @QueryParam("nextToken") String nextToken) {
        List<DataZoneDomain> all = service.listDomains(region(headers), status);
        return Response.ok(page(all, maxResults, 25, nextToken, this::domainSummary)).build();
    }

    @GET
    @Path("/{identifier}")
    public Response getDomain(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        return Response.ok(domainBody(service.getDomain(region(headers), identifier))).build();
    }

    @DELETE
    @Path("/{identifier}")
    public Response deleteDomain(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        service.deleteDomain(region(headers), identifier);
        return Response.status(202).entity(Map.of("status", "DELETING")).build();
    }

    // Projects

    @POST
    @Path("/{domainIdentifier}/projects")
    public Response createProject(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                  JsonNode request) {
        JsonNode body = body(request);
        DataZoneProject project = service.createProject(region(headers), domainId, text(body, "name"),
                text(body, "description"), text(body, "domainUnitId"), stringList(body.get("glossaryTerms")));
        return Response.status(201).entity(projectBody(project)).build();
    }

    @GET
    @Path("/{domainIdentifier}/projects")
    public Response listProjects(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                 @QueryParam("name") String name, @QueryParam("maxResults") Integer maxResults,
                                 @QueryParam("nextToken") String nextToken) {
        List<DataZoneProject> all = service.listProjects(region(headers), domainId, name);
        return Response.ok(page(all, maxResults, 50, nextToken, this::projectSummary)).build();
    }

    @GET
    @Path("/{domainIdentifier}/projects/{identifier}")
    public Response getProject(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                               @PathParam("identifier") String identifier) {
        return Response.ok(projectBody(service.getProject(region(headers), domainId, identifier))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/projects/{identifier}")
    public Response deleteProject(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                  @PathParam("identifier") String identifier,
                                  @QueryParam("skipDeletionCheck") boolean skipDeletionCheck) {
        service.deleteProject(region(headers), domainId, identifier, skipDeletionCheck);
        return Response.noContent().build();
    }

    // Glossaries

    @POST
    @Path("/{domainIdentifier}/glossaries")
    public Response createGlossary(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   JsonNode request) {
        JsonNode body = body(request);
        DataZoneGlossary glossary = service.createGlossary(region(headers), domainId, text(body, "name"),
                text(body, "owningProjectIdentifier"), text(body, "description"), text(body, "status"),
                stringList(body.get("usageRestrictions")));
        return Response.status(201).entity(glossaryBody(glossary, false)).build();
    }

    @GET
    @Path("/{domainIdentifier}/glossaries/{identifier}")
    public Response getGlossary(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                @PathParam("identifier") String identifier) {
        return Response.ok(glossaryBody(service.getGlossary(region(headers), domainId, identifier), true)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/glossaries/{identifier}")
    public Response updateGlossary(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("identifier") String identifier, JsonNode request) {
        JsonNode body = body(request);
        DataZoneGlossary glossary = service.updateGlossary(region(headers), domainId, identifier,
                text(body, "name"), text(body, "description"), text(body, "status"));
        return Response.ok(glossaryBody(glossary, false)).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/glossaries/{identifier}")
    public Response deleteGlossary(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("identifier") String identifier) {
        service.deleteGlossary(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    // Glossary terms

    @POST
    @Path("/{domainIdentifier}/glossary-terms")
    public Response createGlossaryTerm(@Context HttpHeaders headers,
                                       @PathParam("domainIdentifier") String domainId, JsonNode request) {
        JsonNode body = body(request);
        DataZoneGlossaryTerm term = service.createGlossaryTerm(region(headers), domainId,
                text(body, "glossaryIdentifier"), text(body, "name"), text(body, "status"),
                text(body, "shortDescription"), text(body, "longDescription"), object(body, "termRelations"));
        return Response.status(201).entity(termBody(term, false)).build();
    }

    @GET
    @Path("/{domainIdentifier}/glossary-terms/{identifier}")
    public Response getGlossaryTerm(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                    @PathParam("identifier") String identifier) {
        return Response.ok(termBody(service.getGlossaryTerm(region(headers), domainId, identifier), true)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/glossary-terms/{identifier}")
    public Response updateGlossaryTerm(@Context HttpHeaders headers,
                                       @PathParam("domainIdentifier") String domainId,
                                       @PathParam("identifier") String identifier, JsonNode request) {
        JsonNode body = body(request);
        DataZoneGlossaryTerm term = service.updateGlossaryTerm(region(headers), domainId, identifier,
                text(body, "glossaryIdentifier"), text(body, "name"), text(body, "status"),
                text(body, "shortDescription"), text(body, "longDescription"), object(body, "termRelations"));
        return Response.ok(termBody(term, false)).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/glossary-terms/{identifier}")
    public Response deleteGlossaryTerm(@Context HttpHeaders headers,
                                       @PathParam("domainIdentifier") String domainId,
                                       @PathParam("identifier") String identifier) {
        service.deleteGlossaryTerm(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    // Response shapes

    private Map<String, Object> domainBody(DataZoneDomain domain) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", domain.getId());
        out.put("rootDomainUnitId", domain.getRootDomainUnitId());
        out.put("name", domain.getName());
        putIfPresent(out, "description", domain.getDescription());
        if (domain.getSingleSignOn() != null) {
            out.put("singleSignOn", domain.getSingleSignOn());
        }
        putIfPresent(out, "domainExecutionRole", domain.getDomainExecutionRole());
        out.put("arn", domain.getArn());
        putIfPresent(out, "kmsKeyIdentifier", domain.getKmsKeyIdentifier());
        out.put("status", domain.getStatus());
        out.put("portalUrl", domain.getPortalUrl());
        out.put("createdAt", epochSeconds(domain.getCreatedAt()));
        out.put("lastUpdatedAt", epochSeconds(domain.getUpdatedAt()));
        out.put("tags", domain.getTags());
        out.put("domainVersion", domain.getDomainVersion());
        putIfPresent(out, "serviceRole", domain.getServiceRole());
        return out;
    }

    private Map<String, Object> domainSummary(DataZoneDomain domain) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", domain.getId());
        out.put("name", domain.getName());
        putIfPresent(out, "description", domain.getDescription());
        out.put("arn", domain.getArn());
        out.put("managedAccountId", domain.getCreatedBy());
        out.put("status", domain.getStatus());
        out.put("portalUrl", domain.getPortalUrl());
        out.put("createdAt", epochSeconds(domain.getCreatedAt()));
        out.put("lastUpdatedAt", epochSeconds(domain.getUpdatedAt()));
        out.put("domainVersion", domain.getDomainVersion());
        return out;
    }

    private Map<String, Object> projectBody(DataZoneProject project) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domainId", project.getDomainId());
        out.put("id", project.getId());
        out.put("name", project.getName());
        putIfPresent(out, "description", project.getDescription());
        out.put("projectStatus", "ACTIVE");
        out.put("createdBy", project.getCreatedBy());
        out.put("createdAt", Instant.ofEpochMilli(project.getCreatedAt()).toString());
        out.put("lastUpdatedAt", Instant.ofEpochMilli(project.getUpdatedAt()).toString());
        out.put("glossaryTerms", project.getGlossaryTerms());
        out.put("domainUnitId", project.getDomainUnitId());
        return out;
    }

    private Map<String, Object> projectSummary(DataZoneProject project) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domainId", project.getDomainId());
        out.put("id", project.getId());
        out.put("name", project.getName());
        putIfPresent(out, "description", project.getDescription());
        out.put("projectStatus", "ACTIVE");
        out.put("createdBy", project.getCreatedBy());
        out.put("createdAt", Instant.ofEpochMilli(project.getCreatedAt()).toString());
        out.put("updatedAt", Instant.ofEpochMilli(project.getUpdatedAt()).toString());
        out.put("domainUnitId", project.getDomainUnitId());
        return out;
    }

    private Map<String, Object> glossaryBody(DataZoneGlossary glossary, boolean withAudit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domainId", glossary.getDomainId());
        out.put("id", glossary.getId());
        out.put("name", glossary.getName());
        out.put("owningProjectId", glossary.getOwningProjectId());
        putIfPresent(out, "description", glossary.getDescription());
        out.put("status", glossary.getStatus());
        if (glossary.getUsageRestrictions() != null) {
            out.put("usageRestrictions", glossary.getUsageRestrictions());
        }
        if (withAudit) {
            out.put("createdAt", epochSeconds(glossary.getCreatedAt()));
            out.put("createdBy", glossary.getCreatedBy());
            out.put("updatedAt", epochSeconds(glossary.getUpdatedAt()));
            out.put("updatedBy", glossary.getUpdatedBy());
        }
        return out;
    }

    private Map<String, Object> termBody(DataZoneGlossaryTerm term, boolean withAudit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", term.getId());
        out.put("domainId", term.getDomainId());
        out.put("glossaryId", term.getGlossaryId());
        out.put("name", term.getName());
        out.put("status", term.getStatus());
        putIfPresent(out, "shortDescription", term.getShortDescription());
        putIfPresent(out, "longDescription", term.getLongDescription());
        if (term.getTermRelations() != null) {
            out.put("termRelations", term.getTermRelations());
        }
        if (withAudit) {
            out.put("createdAt", epochSeconds(term.getCreatedAt()));
            out.put("createdBy", term.getCreatedBy());
            out.put("updatedAt", epochSeconds(term.getUpdatedAt()));
            out.put("updatedBy", term.getUpdatedBy());
        }
        return out;
    }

    // Helpers

    private <T> Map<String, Object> page(List<T> all, Integer maxResults, int maxAllowed, String nextToken,
                                         Function<T, Map<String, Object>> render) {
        int limit = maxResults == null ? maxAllowed : maxResults;
        if (limit < 1 || limit > maxAllowed) {
            throw SmithyValidationErrors.validationException("Value '" + maxResults
                    + "' at 'maxResults' failed to satisfy constraint: Member must have value between 1 and "
                    + maxAllowed);
        }
        int start = decodeToken(nextToken);
        int from = Math.min(start, all.size());
        int to = Math.min(from + limit, all.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (T item : all.subList(from, to)) {
            items.add(render.apply(item));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        if (to < all.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Integer.toString(to).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static int decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            if (offset < 0) {
                throw new NumberFormatException(token);
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw SmithyValidationErrors.validationException("The nextToken is invalid.");
        }
    }

    private static BigDecimal epochSeconds(long millis) {
        return BigDecimal.valueOf(millis).movePointLeft(3);
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private static JsonNode body(JsonNode request) {
        if (request == null || request.isNull()) {
            return MissingNode.getInstance();
        }
        return request;
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static JsonNode object(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isObject() ? value : null;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String key, String value) {
        if (value != null) {
            out.put(key, value);
        }
    }
}
