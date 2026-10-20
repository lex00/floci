package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataZoneSearchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textFiltersAndSortCombine() throws Exception {
        List<ObjectNode> documents = List.of(
                (ObjectNode) mapper.readTree("{\"name\": \"orders\", \"rows\": 10, \"owningProjectId\": \"p1\"}"),
                (ObjectNode) mapper.readTree("{\"name\": \"order lines\", \"rows\": 50, \"owningProjectId\": \"p1\"}"),
                (ObjectNode) mapper.readTree("{\"name\": \"customers\", \"rows\": 5, \"owningProjectId\": \"p2\"}"));

        List<ObjectNode> found = DataZoneSearch.apply(documents, mapper.readTree("""
                {"searchText": "ORDER",
                 "filters": {"and": [{"filter": {"attribute": "owningProjectId", "value": "p1"}},
                                     {"filter": {"attribute": "rows", "intValue": 20, "operator": "GT"}}]},
                 "sort": {"attribute": "name", "order": "DESCENDING"}}"""));
        assertEquals(List.of("order lines"), found.stream().map(d -> d.path("name").asText()).toList());

        List<ObjectNode> sorted = DataZoneSearch.apply(documents, mapper.readTree(
                "{\"sort\": {\"attribute\": \"rows\", \"order\": \"ASCENDING\"}}"));
        assertEquals(List.of("customers", "orders", "order lines"),
                sorted.stream().map(d -> d.path("name").asText()).toList());
    }

    @Test
    void listingFormsKeyContentByFormName() throws Exception {
        assertEquals("{\"Quality\":{\"score\":9},\"Notes\":\"not json\"}", DataZoneCatalogService.forms(mapper.readTree(
                "[{\"formName\": \"Quality\", \"content\": \"{\\\"score\\\": 9}\"},"
                        + " {\"formName\": \"Notes\", \"content\": \"not json\"}]")));
    }
}
