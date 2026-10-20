package io.github.hectorvent.floci.services.codecommit;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.codecommit.model.Repository;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Git's smart HTTP protocol on a CodeCommit repository's clone URL, {@code /v1/repos/<name>}.
 * The host {@code git-codecommit.<region>.localhost} names the region, as AWS's host does, and any
 * other host means the default region. The account is the one in a Git credential user name of the
 * form {@code <name>-at-<account>}, as CodeCommit's Git credentials are, and the default one
 * otherwise. Any password is accepted.
 */
@Path("/v1/repos/{repositoryName}")
public class CodeCommitGitController {

    private static final Pattern REGION_HOST =
            Pattern.compile("^git-codecommit\\.([a-z0-9-]+)\\.localhost(?:\\.floci\\.io)?(?::[0-9]+)?$");
    private static final Pattern CREDENTIAL_ACCOUNT = Pattern.compile("(?:^|-at-)([0-9]{12})$");

    private final CodeCommitService service;
    private final CodeCommitGitServer gitServer;
    private final RegionResolver regionResolver;

    @Inject
    public CodeCommitGitController(CodeCommitService service, CodeCommitGitServer gitServer,
                                   RegionResolver regionResolver) {
        this.service = service;
        this.gitServer = gitServer;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/info/refs")
    public Response infoRefs(@Context HttpHeaders headers, @PathParam("repositoryName") String name,
                             @QueryParam("service") String gitService) {
        if (gitService == null || !CodeCommitGitServer.SERVICES.contains(gitService)) {
            return text(403, "Git's dumb HTTP protocol is not served; use a Git client that speaks smart HTTP.");
        }
        Optional<Repository> repository = repository(headers, name);
        if (repository.isEmpty()) {
            return notFound(name);
        }
        byte[] body = gitServer.advertise(repository.get().getRepositoryId(), gitService,
                headers.getHeaderString("Git-Protocol"));
        return Response.ok(body, "application/x-" + gitService + "-advertisement")
                .header("Cache-Control", "no-cache")
                .build();
    }

    @POST
    @Path("/git-upload-pack")
    @Consumes(MediaType.WILDCARD)
    public Response uploadPack(@Context HttpHeaders headers, @PathParam("repositoryName") String name,
                               InputStream body) throws IOException {
        return rpc(headers, name, "git-upload-pack", body);
    }

    @POST
    @Path("/git-receive-pack")
    @Consumes(MediaType.WILDCARD)
    public Response receivePack(@Context HttpHeaders headers, @PathParam("repositoryName") String name,
                                InputStream body) throws IOException {
        return rpc(headers, name, "git-receive-pack", body);
    }

    private Response rpc(HttpHeaders headers, String name, String gitService, InputStream body) throws IOException {
        Optional<Repository> repository = repository(headers, name);
        if (repository.isEmpty()) {
            return notFound(name);
        }
        InputStream request = "gzip".equalsIgnoreCase(headers.getHeaderString("Content-Encoding"))
                ? new GZIPInputStream(body) : body;
        byte[] result = gitServer.rpc(repository.get().getRepositoryId(), gitService,
                headers.getHeaderString("Git-Protocol"), request);
        return Response.ok(result, "application/x-" + gitService + "-result")
                .header("Cache-Control", "no-cache")
                .build();
    }

    private Optional<Repository> repository(HttpHeaders headers, String name) {
        String host = headers.getHeaderString("Host");
        Matcher region = REGION_HOST.matcher(host == null ? "" : host);
        return service.findRepository(account(headers.getHeaderString("Authorization")),
                region.matches() ? region.group(1) : regionResolver.getDefaultRegion(), name);
    }

    private String account(String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String credentials = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int colon = credentials.indexOf(':');
                Matcher account = CREDENTIAL_ACCOUNT.matcher(colon >= 0 ? credentials.substring(0, colon) : credentials);
                if (account.find()) {
                    return account.group(1);
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed Basic header names no account; the default one applies.
            }
        }
        return regionResolver.getAccountId();
    }

    private static Response notFound(String name) {
        return text(404, "Repository " + name + " does not exist.");
    }

    private static Response text(int status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN).entity(message + "\n").build();
    }
}
