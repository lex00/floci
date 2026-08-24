package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * lex00/floci#124: two RDS instances that both explicitly request the identical port (the
 * common case in the wild - most engines' own examples, and terraform-aws-modules/
 * terraform-aws-rds's own "complete-postgres" example among them, just hardcode the engine's
 * standard port on every instance) must BOTH get that literal port back from
 * CreateDBInstance/DescribeDBInstances - real AWS never reassigns it, since every RDS instance
 * is its own isolated network endpoint - AND a real connection to each instance's own endpoint
 * must reach that instance and only that instance.
 *
 * <p>Round 5 (51a17134) honored the declared port but decoupled it from the real listener,
 * which round 6 (f1b2520c) reverted after RdsJdbcCompatTest.iamAuthRejectedWhenDisabledAtCreate
 * caught a real cross-instance mix-up: a client trusting the declared port silently reached the
 * WRONG instance. This test is the isolation proof for the round 8 fix (a distinct loopback bind
 * address per colliding instance) that makes both properties hold together.
 */
@DisplayName("RDS two instances, identical requested port (#124)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsTwoInstanceSamePortIsolationTest {

    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    private static final Region REGION = Region.US_EAST_1;
    private static final String USERNAME = "admin";
    private static final String PASSWORD_A = "secretA123";
    private static final String PASSWORD_B = "secretB456";
    private static final String DATABASE = "app";
    private static final int REQUESTED_PORT = 5432;

    private static RdsClient rds;
    private static String idA;
    private static String idB;
    private static String hostA;
    private static String hostB;
    private static int portA;
    private static int portB;
    /**
     * lex00/floci#124's documented boundary: instance B's distinct loopback bind address is
     * only reachable from a client sharing floci's own network namespace. This test's own
     * process might be exactly that (e.g. run directly on the same host/container as floci) -
     * or it might be a sibling container reaching floci over a Docker bridge network (the
     * shape of this repo's own "native / sdk-test-java" CI job), which cannot route to an
     * address that only exists inside floci's own namespace. Cached once via {@link
     * #probeInstanceBReachable()} so every B-specific assertion below degrades to a skip
     * (never a false failure OR a false pass) in the topology where it structurally cannot be
     * exercised, while still running for real wherever it can be.
     */
    private static Boolean instanceBReachable;

    @BeforeAll
    static void setup() {
        rds = TestFixtures.rdsClient();
        idA = TestFixtures.uniqueName("rds-porta");
        idB = TestFixtures.uniqueName("rds-portb");
    }

    @AfterAll
    static void cleanup() {
        if (rds != null) {
            for (String id : new String[] {idA, idB}) {
                try {
                    rds.deleteDBInstance(DeleteDbInstanceRequest.builder()
                            .dbInstanceIdentifier(id)
                            .skipFinalSnapshot(true)
                            .build());
                } catch (Exception ignored) { }
            }
            rds.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Both instances get their literal requested port back, never silently reassigned")
    void bothInstancesReportTheIdenticalRequestedPort() {
        CreateDbInstanceResponse a = rds.createDBInstance(CreateDbInstanceRequest.builder()
                .dbInstanceIdentifier(idA)
                .dbInstanceClass("db.t3.micro")
                .engine("postgres")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD_A)
                .dbName(DATABASE)
                .allocatedStorage(20)
                .port(REQUESTED_PORT)
                .enableIAMDatabaseAuthentication(true)
                .build());
        hostA = a.dbInstance().endpoint().address();
        portA = a.dbInstance().endpoint().port();

        CreateDbInstanceResponse b = rds.createDBInstance(CreateDbInstanceRequest.builder()
                .dbInstanceIdentifier(idB)
                .dbInstanceClass("db.t3.micro")
                .engine("postgres")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD_B)
                .dbName(DATABASE)
                .port(REQUESTED_PORT)
                .build());
        hostB = b.dbInstance().endpoint().address();
        portB = b.dbInstance().endpoint().port();

        assertThat(portA).as("first instance keeps its literal requested port")
                .isEqualTo(REQUESTED_PORT);
        assertThat(portB).as("second instance must ALSO keep its literal requested port - "
                + "never silently bumped to a different number on collision (that was the "
                + "corpus-rds-complete-postgres drift)").isEqualTo(REQUESTED_PORT);

        // The two literal listeners can't share one (address, port) pair, so - unlike the
        // declared PORT, which is now always honored - the ADDRESS is where the unavoidable
        // same-host collision has to land instead.
        assertThat(hostA).as("distinct instances must be distinguishable by their full "
                + "endpoint (address, port), exactly like real AWS").isNotEqualTo(hostB);
    }

    @Test
    @Order(2)
    @DisplayName("A real connection to each instance's own endpoint reaches that instance and only that instance")
    void eachInstanceIsReachableAndIsolatedAtItsOwnEndpoint() throws Exception {
        // Both instances are freshly created with no data in "app" beyond the default schema,
        // so isolation is proven by AUTHENTICATION: instance A's master password must be
        // rejected by instance B's real listener, and vice versa. If either connection reached
        // the WRONG instance, the "wrong" password would actually be the RIGHT one there.
        //
        // Instance A always uses the ordinary/default bind address, reachable the same way
        // this test process already reaches floci's own API - so it's asserted unconditionally.
        Connection connA = awaitConnection(hostA, portA, USERNAME, PASSWORD_A);
        try {
            assertThat(selectOne(connA)).isEqualTo(1);
        } finally {
            connA.close();
        }

        Assumptions.assumeTrue(probeInstanceBReachable(), "Instance B's distinct loopback bind "
                + "address (" + hostB + ") is not reachable from this test process - only from "
                + "a client sharing floci's own network namespace. See lex00/floci#124's PR for "
                + "this documented boundary: a sibling container reaching floci over a Docker "
                + "bridge network (this CI job's own topology) cannot route to an address that "
                + "exists only inside floci's own network namespace.");

        Connection connB = awaitConnection(hostB, portB, USERNAME, PASSWORD_B);
        try {
            assertThat(selectOne(connB)).isEqualTo(1);
        } finally {
            connB.close();
        }

        // Cross-wired credentials: A's password must NOT open a connection at B's endpoint,
        // and B's password must NOT open a connection at A's endpoint. This is the direct
        // regression proof for the wrong-instance mix-up f1b2520c reverted round 5's fix over -
        // it fails loudly (auth succeeds where it shouldn't) instead of silently if isolation
        // ever breaks again.
        assertThatThrownBy(() -> openConnection(hostB, portB, USERNAME, PASSWORD_A))
                .as("instance A's password must be rejected by instance B's real listener")
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> openConnection(hostA, portA, USERNAME, PASSWORD_B))
                .as("instance B's password must be rejected by instance A's real listener")
                .isInstanceOf(SQLException.class);
    }

    @Test
    @Order(3)
    @DisplayName("IAM auth token generated for instance A's endpoint works at A, is rejected at B")
    void iamTokenForInstanceADoesNotAuthenticateAtInstanceB() throws Exception {
        // A signed IAM auth token embeds the (host, port) it was generated for. Instance A has
        // IAM enabled; instance B does not. If floci ever regressed to accepting connections at
        // the wrong instance for its declared endpoint, this - not just a plain password - is
        // the check that would catch it: it's exactly the shape of AWS credential floci's own
        // JDBC proxy authenticates, and it's the direct regression proof for the mix-up f1b2520c
        // reverted round 5's fix over (an IAM-signed token meant to be rejected by a second,
        // IAM-disabled instance was accepted by the first, IAM-enabled one instead).
        String tokenForA = rds.utilities().generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                .hostname(hostA)
                .port(portA)
                .username(USERNAME)
                .region(REGION)
                .credentialsProvider(CREDENTIALS)
                .build());

        Connection connA = awaitConnection(hostA, portA, USERNAME, tokenForA);
        try {
            assertThat(selectOne(connA)).as("the token authenticates at the IAM-enabled "
                    + "instance it was generated for").isEqualTo(1);
        } finally {
            connA.close();
        }

        Assumptions.assumeTrue(probeInstanceBReachable(), "Instance B's distinct loopback bind "
                + "address (" + hostB + ") is not reachable from this test process - see "
                + "eachInstanceIsReachableAndIsolatedAtItsOwnEndpoint's assumption for detail.");

        // Dialing instance B using a token minted for instance A's endpoint must fail - either
        // because B rejects the signature (wrong host/port in the signed request) or because B
        // has IAM disabled and treats the token as an ordinary (wrong) password. Either way, it
        // must never succeed.
        assertThatThrownBy(() -> openConnection(hostB, portB, USERNAME, tokenForA))
                .as("a token signed for instance A's endpoint must not authenticate at instance B")
                .isInstanceOf(SQLException.class);
    }

    /**
     * A short, one-time probe that answers "can THIS process open a TCP connection to instance
     * B's distinct bind address at all" - deliberately a raw socket connect, not a full
     * PostgreSQL handshake, so it can't be confused by an unrelated PostgreSQL-level failure
     * (wrong role, auth rejected, backend still bootstrapping) which means the network path DID
     * work and the real assertions below should run and see that failure directly, not have it
     * silently swallowed into a skip. The answer is cached, since it can't change mid-test-class:
     * the topology (same network namespace as floci, or a separate sibling container) is fixed
     * for the whole run. 20s is generous for the genuine "container still starting" case (matches
     * the other connection helpers' pacing) while not burning the full 60s budget every other
     * connection here uses on what is, in the unreachable case, a structural topology fact
     * rather than a transient timing issue.
     */
    private static boolean probeInstanceBReachable() throws Exception {
        if (instanceBReachable != null) {
            return instanceBReachable;
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(hostB, portB), 2000);
                instanceBReachable = true;
                return true;
            } catch (java.io.IOException e) {
                Thread.sleep(1000);
            }
        }
        instanceBReachable = false;
        return false;
    }

    private static Connection awaitConnection(String host, int port, String username, String password)
            throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                return openConnection(host, port, username, password);
            } catch (SQLException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw last != null ? last : new SQLException("Timed out waiting for RDS proxy connection");
    }

    private static Connection openConnection(String host, int port, String username, String password)
            throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("connectTimeout", "5");
        return DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + DATABASE,
                properties);
    }

    private static int selectOne(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select 1")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }
}
