package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TagWriteGuardTest {

    @Test
    void resourceTypeIsTheServiceAndTheResourceSegment() {
        assertEquals("lambda:function",
                TagWriteGuard.resourceTypeOf("arn:aws:lambda:us-east-1:000000000000:function:fn"));
        assertEquals("dynamodb:table",
                TagWriteGuard.resourceTypeOf("arn:aws:dynamodb:us-east-1:000000000000:table/t"));
        assertEquals("scheduler:schedule-group",
                TagWriteGuard.resourceTypeOf("arn:aws:scheduler:us-east-1:000000000000:schedule-group/g"));
        assertEquals("logs:log-group",
                TagWriteGuard.resourceTypeOf("arn:aws-us-gov:logs:us-gov-west-1:000000000000:log-group:/a/b"));
    }

    @Test
    void typelessArnsGetTheirServiceType() {
        assertEquals("sns:topic", TagWriteGuard.resourceTypeOf("arn:aws:sns:us-east-1:000000000000:topic"));
        assertEquals("sqs:queue", TagWriteGuard.resourceTypeOf("arn:aws:sqs:us-east-1:000000000000:queue"));
        assertEquals("s3:bucket", TagWriteGuard.resourceTypeOf("arn:aws:s3:::bucket"));
    }
}
