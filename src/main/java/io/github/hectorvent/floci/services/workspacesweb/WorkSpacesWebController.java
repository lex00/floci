package io.github.hectorvent.floci.services.workspacesweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.workspacesweb.WorkSpacesWebService.Kind;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

/**
 * Amazon WorkSpaces Secure Browser REST JSON controller. Resource ARNs travel as greedy path
 * labels, so a portal's Associate and Disassociate routes are told apart from UpdatePortal and
 * DeletePortal by their trailing segment. Tagging rides the shared {@code /tags/{arn}} routes.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkSpacesWebController {

    private static final String COLLECTIONS = "browserSettings|dataProtectionSettings|ipAccessSettings"
            + "|networkSettings|sessionLoggers|trustStores|userAccessLoggingSettings|userSettings";

    private final WorkSpacesWebService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public WorkSpacesWebController(WorkSpacesWebService service, RegionResolver regionResolver,
                                   ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Routes ────────────────────────────

    @POST
    @Path("/{collection: " + COLLECTIONS + "}")
    public Response createSettings(@PathParam("collection") String collection, @Context HttpHeaders headers,
                                   String body) {
        Kind kind = WorkSpacesWebService.KINDS.get(collection);
        return dispatch("Create" + kind.noun(), kind, null, body, null, headers);
    }

    @GET
    @Path("/{collection: " + COLLECTIONS + "}")
    public Response listSettings(@PathParam("collection") String collection, @Context HttpHeaders headers) {
        Kind kind = WorkSpacesWebService.KINDS.get(collection);
        return dispatch(kind.listAction(), kind, null, null, null, headers);
    }

    @GET
    @Path("/{collection: " + COLLECTIONS + "}/{arn: .+}")
    public Response getSettings(@PathParam("collection") String collection, @PathParam("arn") String arn,
                                @QueryParam("thumbprint") String thumbprint, @Context HttpHeaders headers) {
        Kind kind = WorkSpacesWebService.KINDS.get(collection);
        if ("trustStores".equals(collection) && arn.endsWith("/certificates")) {
            return dispatch("ListTrustStoreCertificates", kind,
                    arn.substring(0, arn.length() - "/certificates".length()), null, null, headers);
        }
        if ("trustStores".equals(collection) && arn.endsWith("/certificate")) {
            return dispatch("GetTrustStoreCertificate", kind,
                    arn.substring(0, arn.length() - "/certificate".length()), null, thumbprint, headers);
        }
        return dispatch("Get" + kind.noun(), kind, arn, null, null, headers);
    }

    @PATCH
    @Path("/{collection: " + COLLECTIONS + "}/{arn: .+}")
    public Response patchSettings(@PathParam("collection") String collection, @PathParam("arn") String arn,
                                  @Context HttpHeaders headers, String body) {
        Kind kind = WorkSpacesWebService.KINDS.get(collection);
        return dispatch("Update" + kind.noun(), kind, arn, body, null, headers);
    }

    @POST
    @Path("/sessionLoggers/{arn: .+}")
    public Response postSessionLogger(@PathParam("arn") String arn, @Context HttpHeaders headers, String body) {
        return dispatch("UpdateSessionLogger", WorkSpacesWebService.KINDS.get("sessionLoggers"), arn, body, null,
                headers);
    }

    @DELETE
    @Path("/{collection: " + COLLECTIONS + "}/{arn: .+}")
    public Response deleteSettings(@PathParam("collection") String collection, @PathParam("arn") String arn,
                                   @Context HttpHeaders headers) {
        Kind kind = WorkSpacesWebService.KINDS.get(collection);
        return dispatch("Delete" + kind.noun(), kind, arn, null, null, headers);
    }

    @POST
    @Path("/portals")
    public Response postPortal(@Context HttpHeaders headers, String body) {
        return dispatch("CreatePortal", null, null, body, null, headers);
    }

    @GET
    @Path("/portals")
    public Response listPortalRoute(@Context HttpHeaders headers) {
        return dispatch("ListPortals", null, null, null, null, headers);
    }

    @GET
    @Path("/portals/{arn: .+}")
    public Response getPortalRoute(@PathParam("arn") String arn, @Context HttpHeaders headers) {
        return dispatch("GetPortal", null, arn, null, null, headers);
    }

    /** UpdatePortal, or an Associate operation when the path ends in a settings segment. */
    @PUT
    @Path("/portals/{path: .+}")
    public Response putPortal(@PathParam("path") String path, @Context UriInfo uriInfo,
                              @Context HttpHeaders headers, String body) {
        int slash = path.lastIndexOf('/');
        Kind kind = slash < 0 ? null : WorkSpacesWebService.ASSOCIATION_PATHS.get(path.substring(slash + 1));
        if (kind == null) {
            return dispatch("UpdatePortal", null, path, body, null, headers);
        }
        return dispatch("Associate" + kind.noun(), kind, path.substring(0, slash), null,
                uriInfo.getQueryParameters().getFirst(kind.arnMember()), headers);
    }

    /** DeletePortal, or a Disassociate operation when the path ends in a settings segment. */
    @DELETE
    @Path("/portals/{path: .+}")
    public Response deletePortalRoute(@PathParam("path") String path, @Context HttpHeaders headers) {
        int slash = path.lastIndexOf('/');
        Kind kind = slash < 0 ? null : WorkSpacesWebService.ASSOCIATION_PATHS.get(path.substring(slash + 1));
        if (kind == null) {
            return dispatch("DeletePortal", null, path, null, null, headers);
        }
        return dispatch("Disassociate" + kind.noun(), kind, path.substring(0, slash), null, null, headers);
    }

    // ──────────────────────────── Operations ────────────────────────────

    /**
     * Runs one operation. {@code arn} is the resource or portal ARN from the path, and
     * {@code argument} is the associated resource ARN or the certificate thumbprint from the query.
     */
    private Response dispatch(String action, Kind kind, String arn, String body, String argument,
                              HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return switch (action) {
            case "CreateBrowserSettings", "CreateDataProtectionSettings", "CreateIpAccessSettings",
                 "CreateNetworkSettings", "CreateSessionLogger", "CreateTrustStore",
                 "CreateUserAccessLoggingSettings", "CreateUserSettings" ->
                    ok(objectMapper.createObjectNode().put(kind.arnMember(),
                            service.create(kind, readTree(body), region)));
            case "ListBrowserSettings", "ListDataProtectionSettings", "ListIpAccessSettings",
                 "ListNetworkSettings", "ListSessionLoggers", "ListTrustStores",
                 "ListUserAccessLoggingSettings", "ListUserSettings" ->
                    list(kind.listMember(), service.list(kind, region));
            case "GetBrowserSettings", "GetDataProtectionSettings", "GetIpAccessSettings",
                 "GetNetworkSettings", "GetSessionLogger", "GetTrustStore",
                 "GetUserAccessLoggingSettings", "GetUserSettings" -> wrap(kind.member(), service.get(kind, arn));
            case "UpdateBrowserSettings", "UpdateDataProtectionSettings", "UpdateIpAccessSettings",
                 "UpdateNetworkSettings", "UpdateSessionLogger",
                 "UpdateUserAccessLoggingSettings", "UpdateUserSettings" ->
                    wrap(kind.member(), service.update(kind, arn, readTree(body)));
            case "UpdateTrustStore" -> {
                service.update(kind, arn, readTree(body));
                yield ok(objectMapper.createObjectNode().put("trustStoreArn", arn));
            }
            case "DeleteBrowserSettings", "DeleteDataProtectionSettings", "DeleteIpAccessSettings",
                 "DeleteNetworkSettings", "DeleteSessionLogger", "DeleteTrustStore",
                 "DeleteUserAccessLoggingSettings", "DeleteUserSettings" -> {
                service.delete(kind, arn);
                yield ok(objectMapper.createObjectNode());
            }
            case "ListTrustStoreCertificates" -> {
                ObjectNode response = objectMapper.createObjectNode();
                response.putArray("certificateList").addAll(service.listTrustStoreCertificates(arn));
                response.put("trustStoreArn", arn);
                yield ok(response);
            }
            case "GetTrustStoreCertificate" -> {
                ObjectNode response = objectMapper.createObjectNode();
                response.put("trustStoreArn", arn);
                response.set("certificate", service.getTrustStoreCertificate(arn, argument));
                yield ok(response);
            }
            case "CreatePortal" -> {
                ObjectNode portal = service.createPortal(readTree(body), region);
                ObjectNode response = objectMapper.createObjectNode();
                response.put("portalArn", portal.path("portalArn").asText());
                response.put("portalEndpoint", portal.path("portalEndpoint").asText());
                yield ok(response);
            }
            case "ListPortals" -> list("portals", service.listPortals(region));
            case "GetPortal" -> wrap("portal", service.getPortal(arn));
            case "UpdatePortal" -> wrap("portal", service.updatePortal(arn, readTree(body)));
            case "DeletePortal" -> {
                service.deletePortal(arn);
                yield ok(objectMapper.createObjectNode());
            }
            case "AssociateBrowserSettings", "AssociateDataProtectionSettings", "AssociateIpAccessSettings",
                 "AssociateNetworkSettings", "AssociateSessionLogger", "AssociateTrustStore",
                 "AssociateUserAccessLoggingSettings", "AssociateUserSettings" -> {
                if (argument == null || argument.isBlank()) {
                    throw new AwsException("ValidationException", kind.arnMember() + " is required.", 400);
                }
                service.associate(arn, kind, argument);
                ObjectNode response = objectMapper.createObjectNode();
                response.put("portalArn", arn);
                response.put(kind.arnMember(), argument);
                yield ok(response);
            }
            case "DisassociateBrowserSettings", "DisassociateDataProtectionSettings",
                 "DisassociateIpAccessSettings", "DisassociateNetworkSettings", "DisassociateSessionLogger",
                 "DisassociateTrustStore", "DisassociateUserAccessLoggingSettings", "DisassociateUserSettings" -> {
                service.disassociate(arn, kind);
                yield ok(objectMapper.createObjectNode());
            }
            default -> throw new AwsException("UnknownOperationException", "Unknown operation: " + action, 400);
        };
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private Response ok(ObjectNode response) {
        return Response.ok(response).build();
    }

    private Response wrap(String member, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(member, value);
        return ok(response);
    }

    private Response list(String member, List<ObjectNode> values) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(member).addAll(values);
        return ok(response);
    }

    private JsonNode readTree(String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (node == null || !node.isObject()) {
                throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
            }
            return node;
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
