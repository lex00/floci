package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A public-facing instance with no routable container address used to report PublicIpAddress
 * 127.0.0.1 and PublicDnsName localhost. Nothing dials either, so the loopback value was not
 * buying reachability, and any reader judging internet connectivity from it concluded there was
 * none. chant-bench grades exactly that field, so the reported address has to look routable.
 *
 * <p>A genuinely routable bridge address is still preferred and left alone.
 */
class Ec2SyntheticPublicIpTest {

    private static final String PREFIX = "54.144";

    @Test
    void theSyntheticAddressIsStableForAnInstanceId() {
        assertEquals(Ec2ContainerManager.syntheticPublicIp(PREFIX, "i-abc123"),
                Ec2ContainerManager.syntheticPublicIp(PREFIX, "i-abc123"),
                "an instance must report the same address across restarts");
    }

    @Test
    void differentInstancesGetDifferentAddresses() {
        assertNotEquals(Ec2ContainerManager.syntheticPublicIp(PREFIX, "i-aaa"),
                Ec2ContainerManager.syntheticPublicIp(PREFIX, "i-bbb"));
    }

    @Test
    void theLastOctetIsAHostAddress() {
        for (int i = 0; i < 500; i++) {
            String[] parts = Ec2ContainerManager.syntheticPublicIp(PREFIX, "i-" + i).split("\\.");
            int last = Integer.parseInt(parts[3]);
            assertTrue(last >= 1 && last <= 254, "octet " + last + " is not a host address");
            int third = Integer.parseInt(parts[2]);
            assertTrue(third >= 0 && third <= 255, "octet " + third + " out of range");
        }
    }

    @Test
    void theAddressIsNotLoopback() {
        assertTrue(Ec2ContainerManager.syntheticPublicIp(PREFIX, "i-abc").startsWith("54.144."),
                "the reported address has to look routable, not like loopback");
    }
}
