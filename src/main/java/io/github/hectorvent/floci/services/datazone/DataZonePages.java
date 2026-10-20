package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Offset pagination and request-body helpers shared by the DataZone controllers. */
final class DataZonePages {

    private DataZonePages() {
    }

    static ObjectNode page(List<? extends JsonNode> all, Integer maxResults, int maxAllowed, String nextToken,
                           String itemsMember) {
        int limit = maxResults == null ? maxAllowed : maxResults;
        if (limit < 1 || limit > maxAllowed) {
            throw SmithyValidationErrors.validationException("Value '" + maxResults
                    + "' at 'maxResults' failed to satisfy constraint: Member must have value between 1 and "
                    + maxAllowed);
        }
        int from = Math.min(decodeToken(nextToken), all.size());
        int to = Math.min(from + limit, all.size());
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.putArray(itemsMember).addAll(all.subList(from, to));
        if (to < all.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Integer.toString(to).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    static JsonNode body(JsonNode request) {
        if (request == null || request.isNull()) {
            return MissingNode.getInstance();
        }
        return request;
    }

    private static int decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            if (offset < 0) {
                throw new NumberFormatException(token);
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw SmithyValidationErrors.validationException("The nextToken is invalid.");
        }
    }
}
