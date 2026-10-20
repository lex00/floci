package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudfront.model.AnycastIpList;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The CloudFront Anycast static IP list operations under {@code /2020-05-31/anycast-ip-list}. */
@Path("/2020-05-31/anycast-ip-list")
public class CloudFrontAnycastIpListController {

    private static final String XML = "application/xml";
    private static final String NS = AwsNamespaces.CLOUDFRONT;

    private final CloudFrontAnycastIpListService service;

    @Inject
    public CloudFrontAnycastIpListController(CloudFrontAnycastIpListService service) {
        this.service = service;
    }

    @POST
    public Response create(String body) {
        try {
            AnycastIpList list = new AnycastIpList();
            list.setName(XmlParser.extractFirst(body, "Name", null));
            list.setIpCount(parseInt(XmlParser.extractFirst(body, "IpCount", null)));
            list.setIpAddressType(XmlParser.extractFirst(body, "IpAddressType", null));
            list.setIpamCidrConfigs(parseIpamCidrConfigs(body));
            AnycastIpList created = service.create(list, parseTags(body));
            return Response.status(202).type(XML).header("ETag", created.getEtag())
                    .entity(xmlList(created)).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    @GET
    @Path("/{Id}")
    public Response get(@PathParam("Id") String id) {
        try {
            AnycastIpList list = service.get(id);
            return Response.ok(xmlList(list), XML).header("ETag", list.getEtag()).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    @GET
    public Response list(@QueryParam("Marker") String marker,
                         @QueryParam("MaxItems") @DefaultValue("100") int maxItems) {
        try {
            List<AnycastIpList> all = service.list();
            int start = 0;
            if (marker != null && !marker.isEmpty()) {
                while (start < all.size() && all.get(start).getId().compareTo(marker) < 0) {
                    start++;
                }
            }
            int end = Math.min(all.size(), start + Math.max(maxItems, 0));
            List<AnycastIpList> page = all.subList(start, end);
            XmlBuilder xml = new XmlBuilder().start("AnycastIpListCollection", NS).start("Items");
            for (AnycastIpList list : page) {
                xml.start("AnycastIpListSummary")
                        .elem("Id", list.getId())
                        .elem("Name", list.getName())
                        .elem("Status", list.getStatus())
                        .elem("Arn", list.getArn())
                        .elem("IpCount", list.getIpCount())
                        .elem("LastModifiedTime", DateTimeFormatter.ISO_INSTANT.format(list.getLastModifiedTime()))
                        .elem("IpAddressType", list.getIpAddressType())
                        .elem("ETag", list.getEtag());
                appendIpamConfig(xml, list);
                xml.end("AnycastIpListSummary");
            }
            xml.end("Items")
                    .elem("Marker", marker != null ? marker : "");
            if (end < all.size()) {
                xml.elem("NextMarker", all.get(end).getId());
            }
            xml.elem("MaxItems", maxItems)
                    .elem("IsTruncated", end < all.size())
                    .elem("Quantity", page.size())
                    .end("AnycastIpListCollection");
            return Response.ok(xml.build(), XML).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    @PUT
    @Path("/{Id}")
    public Response update(@PathParam("Id") String id, @HeaderParam("If-Match") String ifMatch, String body) {
        try {
            List<AnycastIpList.IpamCidrConfig> configs = body != null && body.contains("IpamCidrConfigs")
                    ? parseIpamCidrConfigs(body) : null;
            AnycastIpList list = service.update(id, ifMatch,
                    XmlParser.extractFirst(body, "IpAddressType", null), configs);
            return Response.ok(xmlList(list), XML).header("ETag", list.getEtag()).build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    @DELETE
    @Path("/{Id}")
    public Response delete(@PathParam("Id") String id, @HeaderParam("If-Match") String ifMatch) {
        try {
            service.delete(id, ifMatch);
            return Response.noContent().build();
        } catch (AwsException e) {
            return errorResponse(e);
        }
    }

    private static String xmlList(AnycastIpList list) {
        XmlBuilder xml = new XmlBuilder().start("AnycastIpList", NS);
        xml.elem("Id", list.getId())
                .elem("Name", list.getName())
                .elem("Status", list.getStatus())
                .elem("Arn", list.getArn())
                .elem("IpAddressType", list.getIpAddressType());
        appendIpamConfig(xml, list);
        xml.start("AnycastIps");
        list.getAnycastIps().forEach(ip -> xml.elem("AnycastIp", ip));
        xml.end("AnycastIps")
                .elem("IpCount", list.getIpCount())
                .elem("LastModifiedTime", DateTimeFormatter.ISO_INSTANT.format(list.getLastModifiedTime()))
                .end("AnycastIpList");
        return xml.build();
    }

    private static void appendIpamConfig(XmlBuilder xml, AnycastIpList list) {
        if (list.getIpamCidrConfigs().isEmpty()) {
            return;
        }
        xml.start("IpamConfig").elem("Quantity", list.getIpamCidrConfigs().size()).start("IpamCidrConfigs");
        for (AnycastIpList.IpamCidrConfig config : list.getIpamCidrConfigs()) {
            xml.start("IpamCidrConfig")
                    .elem("Cidr", config.getCidr())
                    .elem("IpamPoolArn", config.getIpamPoolArn());
            if (config.getAnycastIp() != null) {
                xml.elem("AnycastIp", config.getAnycastIp());
            }
            xml.elem("Status", config.getStatus() != null ? config.getStatus() : "advertised")
                    .end("IpamCidrConfig");
        }
        xml.end("IpamCidrConfigs").end("IpamConfig");
    }

    private static List<AnycastIpList.IpamCidrConfig> parseIpamCidrConfigs(String body) {
        List<AnycastIpList.IpamCidrConfig> configs = new ArrayList<>();
        for (Map<String, String> group : XmlParser.extractGroups(body, "IpamCidrConfig")) {
            AnycastIpList.IpamCidrConfig config = new AnycastIpList.IpamCidrConfig();
            config.setCidr(group.get("Cidr"));
            config.setIpamPoolArn(group.get("IpamPoolArn"));
            config.setAnycastIp(group.get("AnycastIp"));
            configs.add(config);
        }
        return configs;
    }

    private static Map<String, String> parseTags(String body) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (Map<String, String> group : XmlParser.extractGroups(body, "Tag")) {
            if (group.get("Key") != null) {
                tags.put(group.get("Key"), group.getOrDefault("Value", ""));
            }
        }
        return tags;
    }

    private static int parseInt(String value) {
        if (value == null) {
            throw new AwsException("InvalidArgument", "IpCount is required.", 400);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidArgument", "IpCount must be an integer.", 400);
        }
    }

    private static Response errorResponse(AwsException e) {
        String xml = new XmlBuilder()
                .start("ErrorResponse", NS)
                .start("Error")
                .elem("Type", "Sender")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage())
                .end("Error")
                .elem("RequestId", "00000000-0000-0000-0000-000000000000")
                .end("ErrorResponse")
                .build();
        return Response.status(e.getHttpStatus()).type(XML).entity(xml).build();
    }
}
