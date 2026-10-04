package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.EksSessionIdentity;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Maps verified instance credentials to node identities using server-owned EC2 metadata. */
@ApplicationScoped
class EksWorkerAuthentication {
    private final IamService iam;
    private final EksService eks;
    private final Ec2Service ec2;
    private final EksAccessEntryService entries;
    private final boolean principalIdentity;

    @Inject
    EksWorkerAuthentication(IamService iam, EksService eks, Ec2Service ec2, EksAccessEntryService entries,
                            EmulatorConfig config) {
        this(iam, eks, ec2, entries, config.services().eks().principalIdentity());
    }

    EksWorkerAuthentication(IamService iam, EksService eks, Ec2Service ec2, EksAccessEntryService entries) {
        this(iam, eks, ec2, entries, false);
    }

    EksWorkerAuthentication(IamService iam, EksService eks, Ec2Service ec2, EksAccessEntryService entries,
                            boolean principalIdentity) {
        this.iam = iam;
        this.eks = eks;
        this.ec2 = ec2;
        this.entries = entries;
        this.principalIdentity = principalIdentity;
    }

    Optional<Map<String, Object>> authenticate(EksTokenValidator.VerifiedToken token, String name,
                                               String account, String region, String createdAt) {
        Optional<EksSessionIdentity> session = iam.findEksSessionIdentity(token.accessKeyId());
        // Keep the existing non-worker compatibility path. Expired/revoked temporary sessions
        // must never fall through to it after signature validation.
        if (session.isEmpty()) {
            return IamService.isTemporaryAccessKey(token.accessKeyId()) ? Optional.empty() : legacyIdentity();
        }
        EksSessionIdentity identity = session.get();
        if (identity.instanceId() == null) {
            return principalIdentity ? principalIdentity(identity, name, account, region, createdAt) : legacyIdentity();
        }
        if (account == null || region == null || createdAt == null || !account.equals(identity.accountId())
                || !region.equals(token.region()) || identity.roleArn() == null || identity.roleId() == null) {
            return Optional.empty();
        }
        Optional<Cluster> cluster = eks.findAuthenticationCluster(account, name)
                .filter(value -> value.getArn() != null && value.getArn().equals(
                        "arn:aws:eks:" + region + ":" + account + ":cluster/" + name))
                .filter(value -> createdAt.equals(String.valueOf(value.getCreatedAt())));
        if (cluster.isEmpty()) {
            return Optional.empty();
        }
        String roleName = identity.roleArn().substring(identity.roleArn().lastIndexOf('/') + 1);
        Optional<IamRole> role = iam.findRole(account, roleName)
                .filter(value -> identity.roleArn().equals(value.getArn()) && identity.roleId().equals(value.getRoleId()));
        if (role.isEmpty()) {
            return Optional.empty();
        }
        try {
            if (entries.workerEntry(cluster.get(), account, identity.roleArn(), identity.roleId()).isEmpty()) {
                return Optional.empty();
            }
        } catch (AwsException rejected) {
            return Optional.empty();
        }
        Optional<Instance> instance = ec2.findInstanceForAccount(account, region, identity.instanceId())
                .filter(value -> value.getState() != null && "running".equals(value.getState().getName()))
                .filter(value -> region.equals(value.getRegion()))
                .filter(value -> profileMatches(value, account, roleName));
        if (instance.isEmpty() || instance.get().getPrivateDnsName() == null
                || instance.get().getPrivateDnsName().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Map.of("username", "system:node:" + instance.get().getPrivateDnsName(),
                "uid", account + ":" + identity.roleId() + ":" + identity.instanceId(),
                "groups", List.of("system:bootstrappers", "system:nodes")));
    }

    /**
     * The assumed-role session's own Kubernetes identity, from its STANDARD access entry, as EKS
     * maps it: the entry's username (its {@code {{SessionName}}} placeholders filled with
     * {@value #SESSION_NAME}, since Floci does not record the session name) and its
     * kubernetesGroups. No entry, no identity: the token is rejected, as on AWS. The cluster's
     * creator keeps cluster-admin through the static-key path above, which is unchanged.
     *
     * <p>Off by default ({@code floci.services.eks.principal-identity}), because the legacy path
     * maps every non-worker credential to {@code system:masters} and existing setups rely on it.
     * With it on, two roles reach the cluster as two users, which is what a per-principal
     * admission policy or RBAC binding needs to see.
     */
    private Optional<Map<String, Object>> principalIdentity(EksSessionIdentity identity, String name,
                                                            String account, String region, String createdAt) {
        if (account == null || region == null || createdAt == null || !account.equals(identity.accountId())
                || identity.roleArn() == null || identity.roleId() == null) {
            return Optional.empty();
        }
        Optional<Cluster> cluster = eks.findAuthenticationCluster(account, name)
                .filter(value -> value.getArn() != null && value.getArn().equals(
                        "arn:aws:eks:" + region + ":" + account + ":cluster/" + name))
                .filter(value -> createdAt.equals(String.valueOf(value.getCreatedAt())));
        if (cluster.isEmpty()) {
            return Optional.empty();
        }
        Optional<AccessEntry> entry;
        try {
            entry = entries.standardEntry(cluster.get(), account, identity.roleArn(), identity.roleId());
        } catch (AwsException rejected) {
            return Optional.empty();
        }
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        String username = entry.get().username()
                .replace("{{SessionNameRaw}}", SESSION_NAME)
                .replace("{{SessionName}}", SESSION_NAME);
        List<String> groups = entry.get().kubernetesGroups() == null ? List.of() : entry.get().kubernetesGroups();
        return Optional.of(Map.of("username", username,
                "uid", account + ":" + identity.roleId(),
                "groups", List.copyOf(groups)));
    }

    static final String SESSION_NAME = "floci-session";

    private boolean profileMatches(Instance instance, String account, String roleName) {
        String arn = instance.getIamInstanceProfileArn();
        if (arn == null || !arn.startsWith("arn:aws:iam::" + account + ":instance-profile/")) {
            return false;
        }
        return iam.findInstanceProfile(account, arn.substring(arn.lastIndexOf('/') + 1))
                .filter(profile -> arn.equals(profile.getArn()))
                .filter(profile -> List.of(roleName).equals(profile.getRoleNames())).isPresent();
    }

    private static Optional<Map<String, Object>> legacyIdentity() {
        return Optional.of(Map.of("username", "floci:aws-iam", "uid", "floci-aws-iam",
                "groups", List.of("system:masters")));
    }
}
