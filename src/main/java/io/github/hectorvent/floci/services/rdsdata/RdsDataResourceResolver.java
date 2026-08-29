package io.github.hectorvent.floci.services.rdsdata;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
class RdsDataResourceResolver {

    private final RdsService rdsService;

    @Inject
    RdsDataResourceResolver(RdsService rdsService) {
        this.rdsService = rdsService;
    }

    DatabaseTarget resolve(String resourceArn) {
        return resolve(resourceArn, null);
    }

    DatabaseTarget resolve(String resourceArn, String requestRegion) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("BadRequestException", "resourceArn is required.", 400);
        }

        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
            if (!"rds".equals(arn.service())) {
                throw new IllegalArgumentException("not an RDS ARN");
            }
            if (requestRegion != null && !requestRegion.isBlank()
                    && !requestRegion.equals(arn.region())) {
                throw new IllegalArgumentException("RDS ARN is outside the request region");
            }
            int separator = arn.resource().indexOf(':');
            if (separator <= 0 || separator == arn.resource().length() - 1) {
                throw new IllegalArgumentException("invalid RDS resource");
            }
            String type = arn.resource().substring(0, separator);
            String id = arn.resource().substring(separator + 1);
            if ("cluster".equals(type)) {
                DbCluster cluster = rdsService.getDbCluster(id, arn.region());
                if (resourceArn.equals(cluster.getDbClusterArn())) {
                    return fromCluster(cluster);
                }
            } else if ("db".equals(type)) {
                DbInstance instance = rdsService.getDbInstance(id, arn.region());
                if (resourceArn.equals(instance.getDbInstanceArn())) {
                    return fromInstance(instance);
                }
            }
        } catch (AwsException e) {
            if (e.getHttpStatus() >= 500) {
                // A server-side failure (the missing-Docker-daemon shape) is its own answer,
                // not a malformed resourceArn.
                throw e;
            }
            // Normalize lookup failures to the RDS Data API error shape below.
        } catch (IllegalArgumentException ignored) {
            // Normalize ARN parsing failures to the RDS Data API error shape below.
        }

        throw new AwsException("BadRequestException",
                "resourceArn does not resolve to a local RDS resource: " + resourceArn, 400);
    }

    private DatabaseTarget fromCluster(DbCluster cluster) {
        DbCluster resolved = hasRuntime(cluster.getContainerHost(), cluster.getContainerPort())
                ? cluster
                : rdsService.ensureClusterBackend(cluster.getDbClusterIdentifier());
        return target(resolved.getDbClusterArn(), resolved.getEngine(), resolved.getContainerHost(),
                resolved.getContainerPort(), resolved.getMasterUsername(), resolved.getMasterPassword(),
                resolved.getDatabaseName());
    }

    private DatabaseTarget fromInstance(DbInstance instance) {
        DbInstance resolved = hasRuntime(instance.getContainerHost(), instance.getContainerPort())
                ? instance
                : rdsService.ensureInstanceBackend(instance.getDbInstanceIdentifier());
        return target(resolved.getDbInstanceArn(), resolved.getEngine(), resolved.getContainerHost(),
                resolved.getContainerPort(), resolved.getMasterUsername(), resolved.getMasterPassword(),
                resolved.getDbName());
    }

    private static boolean hasRuntime(String host, int port) {
        return host != null && !host.isBlank() && port > 0;
    }

    private DatabaseTarget target(String arn, DatabaseEngine engine, String host, int port,
                                  String username, String password, String databaseName) {
        if (!hasRuntime(host, port)) {
            // The Data API is RDS's data plane: it needs a real database, which Floci can only
            // provide through a Docker container. Name the missing daemon rather than reporting a
            // generic runtime failure, and use the Data API's modelled server-side error shape.
            if (!rdsService.isBackendRuntimeAvailable()) {
                throw new AwsException("InternalServerErrorException",
                        "The RDS backing database is unavailable because no Docker daemon is reachable "
                                + "from Floci. DB instance and cluster metadata operations are supported; "
                                + "Data API execution requires Docker.", 500);
            }
            throw new AwsException("BadRequestException",
                    "RDS resource runtime is not available for Data API execution.", 400);
        }
        return new DatabaseTarget(arn, engine, host, port, username, password, databaseName);
    }

    record DatabaseTarget(String arn, DatabaseEngine engine, String host, int port,
                          String username, String password, String databaseName) {
    }
}
