package io.github.hectorvent.floci.services.auditmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.function.Function;

/** AWS Audit Manager REST-JSON routes for assessments, evidence, delegations, reports and the account. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuditManagerAssessmentController {

    private static final String ASSESSMENT = "/assessments/{assessmentId}";
    private static final String FOLDER = ASSESSMENT + "/controlSets/{controlSetId}/evidenceFolders/{folderId}";
    private static final int MAX_PAGE = 1000;

    private final AuditManagerAssessmentService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AuditManagerAssessmentController(AuditManagerAssessmentService service, RegionResolver regionResolver,
                                            ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // Assessments

    @POST
    @Path("/assessments")
    public Response createAssessment(@Context HttpHeaders headers, String body) {
        return ok(wrap("assessment", service.createAssessment(readTree(body), region(headers))));
    }

    @GET
    @Path(ASSESSMENT)
    public Response getAssessment(@PathParam("assessmentId") String assessmentId, @Context HttpHeaders headers) {
        ObjectNode response = wrap("assessment", service.getAssessment(assessmentId, region(headers)));
        response.putObject("userRole");
        return ok(response);
    }

    @PUT
    @Path(ASSESSMENT)
    public Response updateAssessment(@PathParam("assessmentId") String assessmentId, @Context HttpHeaders headers,
                                     String body) {
        return ok(wrap("assessment", service.updateAssessment(assessmentId, readTree(body), region(headers))));
    }

    @DELETE
    @Path(ASSESSMENT)
    public Response deleteAssessment(@PathParam("assessmentId") String assessmentId, @Context HttpHeaders headers) {
        service.deleteAssessment(assessmentId, region(headers));
        return ok(objectMapper.createObjectNode());
    }

    @GET
    @Path("/assessments")
    public Response listAssessments(@QueryParam("status") String status, @QueryParam("maxResults") String maxResults,
                                    @QueryParam("nextToken") String nextToken, @Context HttpHeaders headers) {
        return ok(page("assessmentMetadata", service.listAssessments(status, region(headers)),
                n -> n.path("id").asText(), maxResults, nextToken));
    }

    @PUT
    @Path(ASSESSMENT + "/status")
    public Response updateAssessmentStatus(@PathParam("assessmentId") String assessmentId,
                                           @Context HttpHeaders headers, String body) {
        return ok(wrap("assessment", service.updateAssessmentStatus(assessmentId,
                readTree(body).path("status").asText(null), region(headers))));
    }

    @PUT
    @Path(ASSESSMENT + "/controlSets/{controlSetId}/status")
    public Response updateAssessmentControlSetStatus(@PathParam("assessmentId") String assessmentId,
                                                     @PathParam("controlSetId") String controlSetId,
                                                     @Context HttpHeaders headers, String body) {
        return ok(wrap("controlSet", service.updateAssessmentControlSetStatus(assessmentId, controlSetId,
                readTree(body), region(headers))));
    }

    @PUT
    @Path(ASSESSMENT + "/controlSets/{controlSetId}/controls/{controlId}")
    public Response updateAssessmentControl(@PathParam("assessmentId") String assessmentId,
                                            @PathParam("controlSetId") String controlSetId,
                                            @PathParam("controlId") String controlId, @Context HttpHeaders headers,
                                            String body) {
        return ok(wrap("control", service.updateAssessmentControl(assessmentId, controlSetId, controlId,
                readTree(body), region(headers))));
    }

    // Evidence

    @POST
    @Path(ASSESSMENT + "/controlSets/{controlSetId}/controls/{controlId}/evidence")
    public Response batchImportEvidenceToAssessmentControl(@PathParam("assessmentId") String assessmentId,
                                                           @PathParam("controlSetId") String controlSetId,
                                                           @PathParam("controlId") String controlId,
                                                           @Context HttpHeaders headers, String body) {
        List<ObjectNode> errors = service.batchImportEvidence(assessmentId, controlSetId, controlId,
                readTree(body).path("manualEvidence"), region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("errors").addAll(errors);
        return ok(response);
    }

    @GET
    @Path(ASSESSMENT + "/evidenceFolders")
    public Response getEvidenceFoldersByAssessment(@PathParam("assessmentId") String assessmentId,
                                                   @QueryParam("maxResults") String maxResults,
                                                   @QueryParam("nextToken") String nextToken,
                                                   @Context HttpHeaders headers) {
        return ok(page("evidenceFolders", service.evidenceFolders(assessmentId, null, null, region(headers)),
                n -> n.path("id").asText(), maxResults, nextToken));
    }

    @GET
    @Path(ASSESSMENT + "/evidenceFolders-by-assessment-control/{controlSetId}/{controlId}")
    public Response getEvidenceFoldersByAssessmentControl(@PathParam("assessmentId") String assessmentId,
                                                          @PathParam("controlSetId") String controlSetId,
                                                          @PathParam("controlId") String controlId,
                                                          @QueryParam("maxResults") String maxResults,
                                                          @QueryParam("nextToken") String nextToken,
                                                          @Context HttpHeaders headers) {
        return ok(page("evidenceFolders", service.evidenceFolders(assessmentId, controlSetId, controlId,
                region(headers)), n -> n.path("id").asText(), maxResults, nextToken));
    }

    @GET
    @Path(FOLDER)
    public Response getEvidenceFolder(@PathParam("assessmentId") String assessmentId,
                                      @PathParam("controlSetId") String controlSetId,
                                      @PathParam("folderId") String folderId, @Context HttpHeaders headers) {
        return ok(wrap("evidenceFolder", service.getEvidenceFolder(assessmentId, controlSetId, folderId,
                region(headers))));
    }

    @GET
    @Path(FOLDER + "/evidence")
    public Response getEvidenceByEvidenceFolder(@PathParam("assessmentId") String assessmentId,
                                                @PathParam("controlSetId") String controlSetId,
                                                @PathParam("folderId") String folderId,
                                                @QueryParam("maxResults") String maxResults,
                                                @QueryParam("nextToken") String nextToken,
                                                @Context HttpHeaders headers) {
        return ok(page("evidence", service.evidenceByFolder(assessmentId, controlSetId, folderId, region(headers)),
                n -> n.path("id").asText(), maxResults, nextToken));
    }

    @GET
    @Path(FOLDER + "/evidence/{evidenceId}")
    public Response getEvidence(@PathParam("assessmentId") String assessmentId,
                                @PathParam("controlSetId") String controlSetId, @PathParam("folderId") String folderId,
                                @PathParam("evidenceId") String evidenceId, @Context HttpHeaders headers) {
        return ok(wrap("evidence", service.getEvidence(assessmentId, controlSetId, folderId, evidenceId,
                region(headers))));
    }

    @PUT
    @Path(ASSESSMENT + "/associateToAssessmentReport")
    public Response associateAssessmentReportEvidenceFolder(@PathParam("assessmentId") String assessmentId,
                                                            @Context HttpHeaders headers, String body) {
        service.setReportSelection(assessmentId, readTree(body).path("evidenceFolderId").asText(""), null, true,
                region(headers));
        return ok(objectMapper.createObjectNode());
    }

    @PUT
    @Path(ASSESSMENT + "/disassociateFromAssessmentReport")
    public Response disassociateAssessmentReportEvidenceFolder(@PathParam("assessmentId") String assessmentId,
                                                               @Context HttpHeaders headers, String body) {
        service.setReportSelection(assessmentId, readTree(body).path("evidenceFolderId").asText(""), null, false,
                region(headers));
        return ok(objectMapper.createObjectNode());
    }

    @PUT
    @Path(ASSESSMENT + "/batchAssociateToAssessmentReport")
    public Response batchAssociateAssessmentReportEvidence(@PathParam("assessmentId") String assessmentId,
                                                           @Context HttpHeaders headers, String body) {
        return ok(reportSelection(assessmentId, readTree(body), true, headers));
    }

    @PUT
    @Path(ASSESSMENT + "/batchDisassociateFromAssessmentReport")
    public Response batchDisassociateAssessmentReportEvidence(@PathParam("assessmentId") String assessmentId,
                                                              @Context HttpHeaders headers, String body) {
        return ok(reportSelection(assessmentId, readTree(body), false, headers));
    }

    // Delegations

    @POST
    @Path(ASSESSMENT + "/delegations")
    public Response batchCreateDelegationByAssessment(@PathParam("assessmentId") String assessmentId,
                                                      @Context HttpHeaders headers, String body) {
        return ok(service.batchCreateDelegations(assessmentId, readTree(body).path("createDelegationRequests"),
                region(headers)));
    }

    @PUT
    @Path(ASSESSMENT + "/delegations")
    public Response batchDeleteDelegationByAssessment(@PathParam("assessmentId") String assessmentId,
                                                      @Context HttpHeaders headers, String body) {
        List<ObjectNode> errors = service.batchDeleteDelegations(assessmentId, readTree(body).path("delegationIds"),
                region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("errors").addAll(errors);
        return ok(response);
    }

    @GET
    @Path("/delegations")
    public Response getDelegations(@QueryParam("maxResults") String maxResults,
                                   @QueryParam("nextToken") String nextToken, @Context HttpHeaders headers) {
        return ok(page("delegations", service.getDelegations(region(headers)), n -> n.path("id").asText(),
                maxResults, nextToken));
    }

    // Assessment reports

    @POST
    @Path(ASSESSMENT + "/reports")
    public Response createAssessmentReport(@PathParam("assessmentId") String assessmentId,
                                           @Context HttpHeaders headers, String body) {
        return ok(wrap("assessmentReport", service.createAssessmentReport(assessmentId, readTree(body),
                region(headers))));
    }

    @DELETE
    @Path(ASSESSMENT + "/reports/{reportId}")
    public Response deleteAssessmentReport(@PathParam("assessmentId") String assessmentId,
                                           @PathParam("reportId") String reportId, @Context HttpHeaders headers) {
        service.deleteAssessmentReport(assessmentId, reportId, region(headers));
        return ok(objectMapper.createObjectNode());
    }

    @GET
    @Path(ASSESSMENT + "/reports/{reportId}/url")
    public Response getAssessmentReportUrl(@PathParam("assessmentId") String assessmentId,
                                           @PathParam("reportId") String reportId, @Context HttpHeaders headers) {
        return ok(wrap("preSignedUrl", service.getAssessmentReportUrl(assessmentId, reportId, region(headers))));
    }

    @GET
    @Path("/assessmentReports")
    public Response listAssessmentReports(@QueryParam("maxResults") String maxResults,
                                          @QueryParam("nextToken") String nextToken, @Context HttpHeaders headers) {
        return ok(page("assessmentReports", service.listAssessmentReports(region(headers)),
                n -> n.path("id").asText(), maxResults, nextToken));
    }

    // Account

    @POST
    @Path("/account/registerAccount")
    @Consumes(MediaType.WILDCARD)
    public Response registerAccount(String body) {
        return ok(objectMapper.createObjectNode().put("status", service.registerAccount(readTree(body))));
    }

    @POST
    @Path("/account/deregisterAccount")
    @Consumes(MediaType.WILDCARD)
    public Response deregisterAccount() {
        return ok(objectMapper.createObjectNode().put("status", service.deregisterAccount()));
    }

    @GET
    @Path("/account/status")
    public Response getAccountStatus() {
        return ok(objectMapper.createObjectNode().put("status", service.accountStatus()));
    }

    // Helpers

    private ObjectNode reportSelection(String assessmentId, JsonNode request, boolean selected, HttpHeaders headers) {
        JsonNode ids = request.path("evidenceIds");
        List<ObjectNode> errors = service.setReportSelection(assessmentId,
                request.path("evidenceFolderId").asText(""), ids, selected, region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("evidenceIds", ids.deepCopy());
        response.putArray("errors").addAll(errors);
        return response;
    }

    private ObjectNode page(String member, List<ObjectNode> items, Function<ObjectNode, String> cursor,
                            String maxResults, String nextToken) {
        PaginatedResult<ObjectNode> page = Pagination.paginate(items, cursor,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken, MAX_PAGE,
                "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(member).addAll(page.items());
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    private ObjectNode wrap(String member, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(member, value);
        return response;
    }

    private static Response ok(ObjectNode entity) {
        return Response.ok(entity).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
