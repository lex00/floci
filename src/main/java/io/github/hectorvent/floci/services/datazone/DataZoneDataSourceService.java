package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datazone.model.DataZoneProject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DataZone data sources and their runs, connections and metadata generation runs. Runs finish
 * as soon as they start: a data source run imports no assets and a metadata generation run
 * generates no predictions.
 */
@ApplicationScoped
public class DataZoneDataSourceService extends DataZoneRecords {

    private static final String DATA_SOURCE = "data-source";
    private static final String RUN = "data-source-run";
    private static final String CONNECTION = "connection";
    private static final String METADATA_RUN = "metadata-generation-run";

    private static final Set<String> ENABLE_SETTINGS = Set.of("ENABLED", "DISABLED");
    private static final Set<String> DATA_SOURCE_STATUSES = Set.of("CREATING", "FAILED_CREATION", "READY",
            "UPDATING", "FAILED_UPDATE", "RUNNING", "DELETING", "FAILED_DELETION");
    private static final Set<String> RUN_STATUSES = Set.of("REQUESTED", "RUNNING", "FAILED", "PARTIALLY_SUCCEEDED",
            "SUCCESS");
    private static final Set<String> ACTIVITY_STATUSES = Set.of("FAILED", "PUBLISHING_FAILED", "SUCCEEDED_CREATED",
            "SUCCEEDED_UPDATED", "SKIPPED_ALREADY_IMPORTED", "SKIPPED_ARCHIVED", "SKIPPED_NO_ACCESS", "UNCHANGED");
    private static final Set<String> METADATA_TYPES = Set.of("BUSINESS_DESCRIPTIONS", "BUSINESS_NAMES",
            "BUSINESS_GLOSSARY_ASSOCIATIONS");
    private static final Set<String> METADATA_STATUSES = Set.of("SUBMITTED", "IN_PROGRESS", "CANCELED", "SUCCEEDED",
            "FAILED", "PARTIALLY_SUCCEEDED");
    private static final Set<String> SCOPES = Set.of("DOMAIN", "PROJECT");
    private static final Set<String> SORT_ORDERS = Set.of("ASCENDING", "DESCENDING");

    /** Connection {@code props} member to the connection type it makes. */
    private static final Map<String, String> CONNECTION_TYPES = Map.ofEntries(
            Map.entry("athenaProperties", "ATHENA"),
            Map.entry("hyperPodProperties", "HYPERPOD"),
            Map.entry("iamProperties", "IAM"),
            Map.entry("redshiftProperties", "REDSHIFT"),
            Map.entry("sparkEmrProperties", "SPARK"),
            Map.entry("sparkGlueProperties", "SPARK"),
            Map.entry("s3Properties", "S3"),
            Map.entry("snowflakeProperties", "SNOWFLAKE"),
            Map.entry("amazonQProperties", "AMAZON_Q"),
            Map.entry("mlflowProperties", "MLFLOW"),
            Map.entry("workflowsMwaaProperties", "WORKFLOWS_MWAA"),
            Map.entry("workflowsServerlessProperties", "WORKFLOWS_SERVERLESS"),
            Map.entry("lakehouseProperties", "LAKEHOUSE"),
            Map.entry("vpcProperties", "VPC"),
            Map.entry("gitProperties", "GIT"));

    private static final String[] DATA_SOURCE_SUMMARY = {"domainId", "environmentId", "connectionId", "name", "type",
            "status", "enableSetting", "schedule", "lastRunStatus", "lastRunAt", "lastRunErrorMessage",
            "lastRunAssetCount", "createdAt", "updatedAt", "description"};
    private static final String[] RUN_SUMMARY = {"id", "dataSourceId", "type", "status", "projectId",
            "runStatisticsForAssets", "errorMessage", "createdAt", "updatedAt", "startedAt", "stoppedAt"};
    private static final String[] CONNECTION_SUMMARY = {"configurations", "connectionId", "domainId", "domainUnitId",
            "environmentId", "name", "physicalEndpoints", "projectId", "props", "scope", "type"};
    private static final String[] METADATA_RUN_ITEM = {"domainId", "id", "target", "status", "type", "types",
            "createdAt", "createdBy", "owningProjectId"};

    private final DataZoneCatalogService catalog;
    private final DataZoneEnvironmentService environments;

    @Inject
    public DataZoneDataSourceService(StorageFactory storageFactory, DataZoneService domains,
                                     DataZoneCatalogService catalog, DataZoneEnvironmentService environments,
                                     RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-data-sources.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, catalog, environments, regionResolver);
    }

    DataZoneDataSourceService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                              DataZoneCatalogService catalog, DataZoneEnvironmentService environments,
                              RegionResolver regionResolver) {
        super(records, domains, regionResolver);
        this.catalog = catalog;
        this.environments = environments;
    }

    // ──────────────────────────── Data sources ────────────────────────────

    public synchronized ObjectNode createDataSource(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String name = requiredText(request, "name");
        String type = requiredText(request, "type");
        DataZoneProject project = domains.getProject(region, domainId, requiredText(request, "projectIdentifier"));
        requireEnum(text(request, "enableSetting"), "enableSetting", ENABLE_SETTINGS);
        String environmentId = text(request, "environmentIdentifier");
        ObjectNode environment = environmentId == null ? null
                : environments.getEnvironment(region, domainId, environmentId);
        String connectionId = text(request, "connectionIdentifier");
        ObjectNode connection = connectionId == null ? null
                : require(CONNECTION, region, domainId, connectionId, "Connection");
        boolean taken = list(DATA_SOURCE, region, domainId).stream()
                .anyMatch(d -> project.getId().equals(d.path("projectId").asText())
                        && name.equals(d.path("name").asText()));
        if (taken) {
            throw conflict("A data source with name " + name + " already exists in the project.");
        }
        String now = dateTime();
        ObjectNode dataSource = JSON.objectNode();
        dataSource.put("id", DataZoneService.randomId());
        dataSource.put("status", "READY");
        dataSource.put("type", type);
        dataSource.put("name", name);
        copy(request, dataSource, "description");
        dataSource.put("domainId", domainId);
        dataSource.put("projectId", project.getId());
        if (environment != null) {
            dataSource.put("environmentId", environment.path("id").asText());
        }
        if (connection != null) {
            dataSource.put("connectionId", connectionId);
        }
        dataSource.set("configuration", configuration(region, request.path("configuration"), environment,
                connection));
        copy(request, dataSource, "recommendation", "schedule");
        dataSource.put("enableSetting", request.path("enableSetting").asText("ENABLED"));
        dataSource.put("publishOnImport", request.path("publishOnImport").asBoolean(false));
        dataSource.set("assetFormsOutput", formsOutput(request.path("assetFormsInput")));
        dataSource.put("createdAt", now);
        dataSource.put("updatedAt", now);
        put(DATA_SOURCE, region, domainId, dataSource.path("id").asText(), dataSource);
        return dataSource.deepCopy();
    }

    public synchronized ObjectNode getDataSource(String region, String domainIdentifier, String identifier) {
        return require(DATA_SOURCE, region, domainId(region, domainIdentifier), identifier, "Data source")
                .deepCopy();
    }

    public synchronized ObjectNode updateDataSource(String region, String domainIdentifier, String identifier,
                                                    JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode dataSource = require(DATA_SOURCE, region, domainId, identifier, "Data source");
        requireEnum(text(request, "enableSetting"), "enableSetting", ENABLE_SETTINGS);
        String name = text(request, "name");
        if (name != null && !name.equals(dataSource.path("name").asText())) {
            boolean taken = list(DATA_SOURCE, region, domainId).stream()
                    .anyMatch(d -> dataSource.path("projectId").asText().equals(d.path("projectId").asText())
                            && name.equals(d.path("name").asText()));
            if (taken) {
                throw conflict("A data source with name " + name + " already exists in the project.");
            }
        }
        copy(request, dataSource, "name", "description", "enableSetting", "publishOnImport", "schedule",
                "recommendation", "retainPermissionsOnRevokeFailure");
        if (request.path("assetFormsInput").isArray()) {
            dataSource.set("assetFormsOutput", formsOutput(request.get("assetFormsInput")));
        }
        if (request.hasNonNull("configuration")) {
            String environmentId = text(dataSource, "environmentId");
            String connectionId = text(dataSource, "connectionId");
            dataSource.set("configuration", configuration(region, request.get("configuration"),
                    environmentId == null ? null : environments.getEnvironment(region, domainId, environmentId),
                    connectionId == null ? null : find(CONNECTION, region, domainId, connectionId).orElse(null)));
        }
        dataSource.put("updatedAt", dateTime());
        put(DATA_SOURCE, region, domainId, identifier, dataSource);
        return dataSource.deepCopy();
    }

    public synchronized ObjectNode deleteDataSource(String region, String domainIdentifier, String identifier,
                                                    Boolean retainPermissionsOnRevokeFailure) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode dataSource = require(DATA_SOURCE, region, domainId, identifier, "Data source");
        for (ObjectNode run : list(RUN, region, domainId)) {
            if (identifier.equals(run.path("dataSourceId").asText())) {
                delete(RUN, region, domainId, run.path("id").asText());
            }
        }
        delete(DATA_SOURCE, region, domainId, identifier);
        dataSource.put("status", "DELETING");
        if (retainPermissionsOnRevokeFailure != null) {
            dataSource.put("retainPermissionsOnRevokeFailure", retainPermissionsOnRevokeFailure);
        }
        return dataSource;
    }

    public synchronized List<ObjectNode> listDataSources(String region, String domainIdentifier,
                                                         Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        String projectId = query.get("projectIdentifier");
        if (projectId == null) {
            throw required("projectIdentifier");
        }
        requireEnum(query.get("status"), "status", DATA_SOURCE_STATUSES);
        return newestFirst(list(DATA_SOURCE, region, domainId).stream()
                .filter(d -> projectId.equals(d.path("projectId").asText()))
                .filter(d -> matches(query.get("environmentIdentifier"), d.path("environmentId")))
                .filter(d -> matches(query.get("connectionIdentifier"), d.path("connectionId")))
                .filter(d -> matches(query.get("type"), d.path("type")))
                .filter(d -> matches(query.get("status"), d.path("status")))
                .filter(d -> matches(query.get("name"), d.path("name")))
                .map(d -> pick(d, DATA_SOURCE_SUMMARY).put("dataSourceId", d.path("id").asText()))
                .toList());
    }

    /** {@code DataSourceConfigurationInput} as {@code DataSourceConfigurationOutput}. */
    private ObjectNode configuration(String region, JsonNode input, ObjectNode environment, ObjectNode connection) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return null;
        }
        String member = unionMember(input, "configuration");
        ObjectNode out = JSON.objectNode();
        ObjectNode run = out.putObject(member);
        JsonNode location = connection == null ? null
                : connection.path("physicalEndpoints").path(0).path("awsLocation");
        if (environment != null) {
            run.put("accountId", environment.path("awsAccountId").asText(account()));
            run.put("region", environment.path("awsAccountRegion").asText(region));
        } else if (location != null && location.isObject()) {
            run.put("accountId", location.path("awsAccountId").asText(account()));
            run.put("region", location.path("awsRegion").asText(region));
        } else {
            run.put("accountId", account());
            run.put("region", region);
        }
        input.get(member).fields().forEachRemaining(e -> run.set(e.getKey(), e.getValue().deepCopy()));
        return out;
    }

    private static ArrayNode formsOutput(JsonNode forms) {
        ArrayNode out = JSON.arrayNode();
        for (JsonNode form : forms) {
            ObjectNode output = out.addObject();
            output.put("formName", requiredText(form, "formName"));
            if (form.hasNonNull("typeIdentifier")) {
                output.put("typeName", form.get("typeIdentifier").asText());
            }
            copy(form, output, "typeRevision", "content");
        }
        return out;
    }

    // ──────────────────────────── Data source runs ────────────────────────────

    public synchronized ObjectNode startDataSourceRun(String region, String domainIdentifier,
                                                      String dataSourceIdentifier) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode dataSource = require(DATA_SOURCE, region, domainId, dataSourceIdentifier, "Data source");
        String now = dateTime();
        ObjectNode run = JSON.objectNode();
        run.put("domainId", domainId);
        run.put("dataSourceId", dataSourceIdentifier);
        run.put("id", DataZoneService.randomId());
        run.put("projectId", dataSource.path("projectId").asText());
        run.put("status", "SUCCESS");
        run.put("type", "PRIORITIZED");
        run.put("dataSourceConfigurationSnapshot", dataSource.path("configuration").toString());
        run.putObject("runStatisticsForAssets")
                .put("added", 0).put("updated", 0).put("unchanged", 0).put("skipped", 0).put("failed", 0);
        run.put("createdAt", now);
        run.put("updatedAt", now);
        run.put("startedAt", now);
        run.put("stoppedAt", now);
        put(RUN, region, domainId, run.path("id").asText(), run);
        dataSource.put("lastRunStatus", "SUCCESS");
        dataSource.put("lastRunAt", now);
        dataSource.put("lastRunAssetCount", 0);
        put(DATA_SOURCE, region, domainId, dataSourceIdentifier, dataSource);
        return run.deepCopy();
    }

    public synchronized ObjectNode getDataSourceRun(String region, String domainIdentifier, String identifier) {
        ObjectNode run = require(RUN, region, domainId(region, domainIdentifier), identifier, "Data source run");
        run.putObject("lineageSummary").put("importStatus", "SUCCESS");
        return run;
    }

    public synchronized List<ObjectNode> listDataSourceRuns(String region, String domainIdentifier,
                                                            String dataSourceIdentifier, Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        require(DATA_SOURCE, region, domainId, dataSourceIdentifier, "Data source");
        requireEnum(query.get("status"), "status", RUN_STATUSES);
        return newestFirst(list(RUN, region, domainId).stream()
                .filter(r -> dataSourceIdentifier.equals(r.path("dataSourceId").asText()))
                .filter(r -> matches(query.get("status"), r.path("status")))
                .map(r -> pick(r, RUN_SUMMARY))
                .toList());
    }

    public synchronized List<ObjectNode> listDataSourceRunActivities(String region, String domainIdentifier,
                                                                     String identifier, Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        require(RUN, region, domainId, identifier, "Data source run");
        requireEnum(query.get("status"), "status", ACTIVITY_STATUSES);
        return List.of();
    }

    // ──────────────────────────── Connections ────────────────────────────

    public synchronized ObjectNode createConnection(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String name = requiredText(request, "name");
        requireEnum(text(request, "scope"), "scope", SCOPES);
        String environmentId = text(request, "environmentIdentifier");
        ObjectNode environment = environmentId == null ? null
                : environments.getEnvironment(region, domainId, environmentId);
        String projectId = environment == null ? null : environment.path("projectId").asText();
        boolean taken = list(CONNECTION, region, domainId).stream()
                .anyMatch(c -> name.equals(c.path("name").asText())
                        && (projectId == null ? !c.has("projectId") : projectId.equals(c.path("projectId").asText())));
        if (taken) {
            throw conflict("A connection with name " + name + " already exists.");
        }
        ObjectNode connection = JSON.objectNode();
        connection.put("connectionId", DataZoneService.randomId());
        connection.put("name", name);
        copy(request, connection, "description", "configurations");
        connection.put("domainId", domainId);
        if (environment != null) {
            connection.put("environmentId", environmentId);
            connection.put("projectId", projectId);
            String domainUnitId = domains.getProject(region, domainId, projectId).getDomainUnitId();
            if (domainUnitId != null) {
                connection.put("domainUnitId", domainUnitId);
            }
        }
        connection.put("scope", request.path("scope").asText(environment == null ? "DOMAIN" : "PROJECT"));
        JsonNode props = request.path("props");
        String member = props.isObject() ? unionMember(props, "props") : null;
        if (member != null) {
            connection.set("propsInput", props.deepCopy());
            String type = "glueProperties".equals(member)
                    ? props.path(member).path("glueConnectionInput").path("connectionType").asText(null)
                    : CONNECTION_TYPES.get(member);
            if (type != null) {
                connection.put("type", type);
            }
        }
        ObjectNode location = JSON.objectNode();
        JsonNode requested = request.path("awsLocation");
        location.put("awsAccountId", requested.path("awsAccountId").asText(environment != null
                ? environment.path("awsAccountId").asText(account()) : account()));
        location.put("awsRegion", requested.path("awsRegion").asText(environment != null
                ? environment.path("awsAccountRegion").asText(region) : region));
        copy(requested, location, "accessRole", "iamConnectionId");
        connection.set("awsLocation", location);
        connection.put("enableTrustedIdentityPropagation",
                request.path("enableTrustedIdentityPropagation").asBoolean(false));
        connection.put("createdAt", now());
        derive(connection);
        put(CONNECTION, region, domainId, connection.path("connectionId").asText(), connection);
        return publicConnection(connection);
    }

    public synchronized ObjectNode getConnection(String region, String domainIdentifier, String identifier) {
        return publicConnection(require(CONNECTION, region, domainId(region, domainIdentifier), identifier,
                "Connection"));
    }

    public synchronized ObjectNode updateConnection(String region, String domainIdentifier, String identifier,
                                                    JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode connection = require(CONNECTION, region, domainId, identifier, "Connection");
        copy(request, connection, "description", "configurations");
        if (request.path("awsLocation").isObject()) {
            ObjectNode location = connection.with("awsLocation");
            request.get("awsLocation").fields().forEachRemaining(e -> location.set(e.getKey(), e.getValue()));
        }
        JsonNode patch = request.path("props");
        if (patch.isObject()) {
            String member = unionMember(patch, "props");
            ObjectNode current = connection.with("propsInput");
            if (!current.has(member)) {
                throw SmithyValidationErrors.validationException("props." + member
                        + " does not match the connection's type.");
            }
            ObjectNode target = (ObjectNode) current.get(member);
            patch.get(member).fields().forEachRemaining(e -> target.set(e.getKey(), e.getValue().deepCopy()));
        }
        derive(connection);
        put(CONNECTION, region, domainId, identifier, connection);
        return publicConnection(connection);
    }

    public synchronized void deleteConnection(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        require(CONNECTION, region, domainId, identifier, "Connection");
        boolean inUse = list(DATA_SOURCE, region, domainId).stream()
                .anyMatch(d -> identifier.equals(d.path("connectionId").asText()));
        if (inUse) {
            throw conflict("Connection " + identifier + " is used by a data source.");
        }
        delete(CONNECTION, region, domainId, identifier);
    }

    public synchronized List<ObjectNode> listConnections(String region, String domainIdentifier,
                                                         Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(query.get("sortBy"), "sortBy", Set.of("NAME"));
        requireEnum(query.get("sortOrder"), "sortOrder", SORT_ORDERS);
        requireEnum(query.get("scope"), "scope", SCOPES);
        Comparator<ObjectNode> order = Comparator.comparing(c -> c.path("name").asText());
        return list(CONNECTION, region, domainId).stream()
                .filter(c -> matches(query.get("name"), c.path("name")))
                .filter(c -> matches(query.get("environmentIdentifier"), c.path("environmentId")))
                .filter(c -> matches(query.get("projectIdentifier"), c.path("projectId")))
                .filter(c -> matches(query.get("type"), c.path("type")))
                .filter(c -> matches(query.get("scope"), c.path("scope")))
                .sorted("DESCENDING".equals(query.get("sortOrder")) ? order.reversed() : order)
                .map(c -> pick(c, CONNECTION_SUMMARY))
                .toList();
    }

    /** Fills a connection's {@code props} output and physical endpoint from what was given. */
    private static void derive(ObjectNode connection) {
        ObjectNode endpoint = JSON.objectNode();
        endpoint.set("awsLocation", connection.path("awsLocation").deepCopy());
        endpoint.put("enableTrustedIdentityPropagation",
                connection.path("enableTrustedIdentityPropagation").asBoolean(false));
        ObjectNode props = JSON.objectNode();
        JsonNode input = connection.path("propsInput");
        if (input.isObject() && input.size() == 1) {
            String member = input.fieldNames().next();
            JsonNode given = input.get(member);
            ObjectNode out = props.putObject(member);
            switch (member) {
                case "glueProperties" -> {
                    out.put("status", "READY");
                    String glueName = given.path("glueConnectionInput").path("name").asText(null);
                    if (glueName != null) {
                        endpoint.put("glueConnectionName", glueName);
                        endpoint.putArray("glueConnectionNames").add(glueName);
                    }
                }
                case "iamProperties" -> {
                    copy(given, out, "glueLineageSyncEnabled");
                    copy(connection, out, "environmentId");
                }
                case "redshiftProperties" -> {
                    copy(given, out, "storage", "databaseName", "lineageSync");
                    if (given.path("credentials").hasNonNull("secretArn")) {
                        out.putObject("credentials").put("secretArn", given.path("credentials")
                                .path("secretArn").asText());
                    }
                    out.put("isProvisionedSecret", false);
                    out.put("status", "READY");
                    if (given.hasNonNull("host")) {
                        int port = given.path("port").asInt(5439);
                        String url = "jdbc:redshift://" + given.get("host").asText() + ":" + port + "/"
                                + given.path("databaseName").asText("dev");
                        out.put("jdbcUrl", url);
                        out.put("jdbcIamUrl", url.replace("jdbc:redshift:", "jdbc:redshift:iam:"));
                        endpoint.put("host", given.get("host").asText());
                        endpoint.put("port", port);
                    }
                }
                case "s3Properties" -> {
                    copy(given, out, "s3Uri", "s3AccessGrantLocationId", "registerS3AccessGrantLocation");
                    out.put("status", "READY");
                }
                default -> given.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue().deepCopy()));
            }
        }
        connection.set("props", props);
        connection.putArray("physicalEndpoints").add(endpoint);
    }

    private static ObjectNode publicConnection(ObjectNode connection) {
        ObjectNode out = connection.deepCopy();
        out.remove(List.of("propsInput", "awsLocation", "enableTrustedIdentityPropagation", "createdAt"));
        return out;
    }

    // ──────────────────────────── Metadata generation runs ────────────────────────────

    public synchronized ObjectNode startMetadataGenerationRun(String region, String domainIdentifier,
                                                              JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        JsonNode target = requiredNode(request, "target");
        requireEnum(requiredText(target, "type"), "target.type", Set.of("ASSET"));
        String assetId = requiredText(target, "identifier");
        DataZoneProject project = domains.getProject(region, domainId,
                requiredText(request, "owningProjectIdentifier"));
        ObjectNode asset = catalog.find(DataZoneCatalogService.ASSET, region, domainId, assetId)
                .orElseThrow(() -> notFound("Asset " + assetId + " not found."));
        ArrayNode types = JSON.arrayNode();
        for (JsonNode type : request.path("types")) {
            types.add(requireEnum(type.asText(), "types", METADATA_TYPES));
        }
        String type = requireEnum(text(request, "type"), "type", METADATA_TYPES);
        if (type != null && types.isEmpty()) {
            types.add(type);
        }
        if (types.isEmpty()) {
            throw required("types");
        }
        ObjectNode run = JSON.objectNode();
        run.put("domainId", domainId);
        run.put("id", DataZoneService.randomId());
        ObjectNode runTarget = run.putObject("target");
        runTarget.put("type", "ASSET");
        runTarget.put("identifier", assetId);
        runTarget.put("revision", DataZoneCatalogService.revision(asset, text(target, "revision"), "Asset")
                .path("revision").asText());
        run.put("status", "SUCCEEDED");
        run.put("type", types.get(0).asText());
        run.set("types", types);
        run.put("createdAt", now());
        run.put("createdBy", account());
        run.put("owningProjectId", project.getId());
        put(METADATA_RUN, region, domainId, run.path("id").asText(), run);
        ObjectNode out = run.deepCopy();
        out.remove("target");
        return out;
    }

    public synchronized ObjectNode getMetadataGenerationRun(String region, String domainIdentifier,
                                                            String identifier, String type) {
        ObjectNode run = require(METADATA_RUN, region, domainId(region, domainIdentifier), identifier,
                "Metadata generation run");
        requireEnum(type, "type", METADATA_TYPES);
        ArrayNode stats = run.putArray("typeStats");
        for (JsonNode runType : run.path("types")) {
            if (type == null || type.equals(runType.asText())) {
                stats.addObject().put("type", runType.asText()).put("status", run.path("status").asText());
            }
        }
        return run;
    }

    public synchronized List<ObjectNode> listMetadataGenerationRuns(String region, String domainIdentifier,
                                                                    Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(query.get("status"), "status", METADATA_STATUSES);
        requireEnum(query.get("type"), "type", METADATA_TYPES);
        return newestFirst(list(METADATA_RUN, region, domainId).stream()
                .filter(r -> matches(query.get("status"), r.path("status")))
                .filter(r -> query.get("type") == null || containsText(r.path("types"), query.get("type")))
                .filter(r -> matches(query.get("targetIdentifier"), r.path("target").path("identifier")))
                .map(r -> pick(r, METADATA_RUN_ITEM))
                .toList());
    }

    public synchronized void cancelMetadataGenerationRun(String region, String domainIdentifier,
                                                         String identifier) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode run = require(METADATA_RUN, region, domainId, identifier, "Metadata generation run");
        String status = run.path("status").asText();
        if (!"SUBMITTED".equals(status) && !"IN_PROGRESS".equals(status)) {
            throw conflict("Metadata generation run " + identifier + " is " + status + ".");
        }
        run.put("status", "CANCELED");
        put(METADATA_RUN, region, domainId, identifier, run);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static boolean containsText(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String expected, JsonNode actual) {
        return expected == null || expected.equals(actual.asText());
    }

    /** Sorts on {@code createdAt}, which is a date-time string or epoch seconds depending on the shape. */
    private static List<ObjectNode> newestFirst(List<ObjectNode> records) {
        Comparator<ObjectNode> order = Comparator.comparing((ObjectNode r) -> r.path("createdAt").isNumber()
                ? r.path("createdAt").decimalValue() : new BigDecimal(Instant.parse(r.path("createdAt").asText())
                        .toEpochMilli()));
        return records.stream().sorted(order.reversed()).toList();
    }

    /** The {@code DateTime} shape data sources and their runs use, unlike the epoch seconds elsewhere. */
    private static String dateTime() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
