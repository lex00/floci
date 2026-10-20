package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Text search, {@code FilterClause} filters and {@code SearchSort} ordering for the DataZone
 * search operations. Attributes name top-level members of the searched document.
 */
final class DataZoneSearch {

    private static final Set<String> OPERATORS = Set.of("EQ", "LE", "LT", "GE", "GT", "TEXT_SEARCH");
    private static final List<String> DEFAULT_TEXT_ATTRIBUTES = List.of("name", "description");

    private DataZoneSearch() {
    }

    /** The documents that match the request's searchText, searchIn and filters, in its sort order. */
    static List<ObjectNode> apply(List<ObjectNode> documents, JsonNode request) {
        String text = request.path("searchText").asText("").toLowerCase(Locale.ROOT);
        List<String> attributes = new ArrayList<>();
        request.path("searchIn").forEach(item -> attributes.add(item.path("attribute").asText()));
        if (attributes.isEmpty()) {
            attributes.addAll(DEFAULT_TEXT_ATTRIBUTES);
        }
        JsonNode filters = request.get("filters");
        List<ObjectNode> matches = documents.stream()
                .filter(d -> text.isEmpty() || attributes.stream().anyMatch(a -> contains(d.get(a), text)))
                .filter(d -> filters == null || filters.isNull() || matches(d, filters))
                .toList();
        JsonNode sort = request.get("sort");
        if (sort == null || !sort.isObject()) {
            return matches;
        }
        String attribute = sort.path("attribute").asText();
        Comparator<ObjectNode> order = Comparator.comparing(d -> d.path(attribute), DataZoneSearch::compare);
        return matches.stream()
                .sorted("DESCENDING".equals(sort.path("order").asText()) ? order.reversed() : order)
                .toList();
    }

    private static boolean matches(ObjectNode document, JsonNode clause) {
        if (clause.has("and")) {
            for (JsonNode inner : clause.get("and")) {
                if (!matches(document, inner)) {
                    return false;
                }
            }
            return true;
        }
        if (clause.has("or")) {
            for (JsonNode inner : clause.get("or")) {
                if (matches(document, inner)) {
                    return true;
                }
            }
            return false;
        }
        JsonNode filter = clause.path("filter");
        String operator = filter.path("operator").asText("EQ");
        if (!OPERATORS.contains(operator)) {
            throw SmithyValidationErrors.validationException("Value '" + operator
                    + "' at 'filters.filter.operator' failed to satisfy constraint: Member must satisfy enum value set: "
                    + OPERATORS);
        }
        JsonNode value = document.get(filter.path("attribute").asText());
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                if (test(element, filter, operator)) {
                    return true;
                }
            }
            return false;
        }
        return test(value, filter, operator);
    }

    private static boolean test(JsonNode value, JsonNode filter, String operator) {
        if ("TEXT_SEARCH".equals(operator)) {
            return contains(value, filter.path("value").asText("").toLowerCase(Locale.ROOT));
        }
        if (filter.has("intValue")) {
            if (!value.isNumber() && !value.isTextual()) {
                return false;
            }
            double actual = value.isNumber() ? value.asDouble() : parse(value.asText());
            double expected = filter.get("intValue").asDouble();
            return switch (operator) {
                case "LE" -> actual <= expected;
                case "LT" -> actual < expected;
                case "GE" -> actual >= expected;
                case "GT" -> actual > expected;
                default -> actual == expected;
            };
        }
        int comparison = value.asText().compareTo(filter.path("value").asText());
        return switch (operator) {
            case "LE" -> comparison <= 0;
            case "LT" -> comparison < 0;
            case "GE" -> comparison >= 0;
            case "GT" -> comparison > 0;
            default -> comparison == 0;
        };
    }

    private static boolean contains(JsonNode value, String text) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                if (contains(element, text)) {
                    return true;
                }
            }
            return false;
        }
        return value.asText().toLowerCase(Locale.ROOT).contains(text);
    }

    private static int compare(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue());
        }
        return left.asText().compareTo(right.asText());
    }

    private static double parse(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
