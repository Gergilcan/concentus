package com.concentus.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiCatalogTest {

    private final OpenApiCatalog catalog = new OpenApiCatalog(new ObjectMapper());

    /** A miniature but structurally honest spec: path+op params, a $ref body, a server. */
    private static final String SPEC = """
            {
              "openapi": "3.0.3",
              "servers": [{"url": "https://api.example.com/v1"}],
              "components": {"schemas": {"NewPet": {
                "type": "object",
                "properties": {"name": {"type": "string"}},
                "required": ["name"]
              }}},
              "paths": {
                "/pets": {
                  "get": {
                    "operationId": "listPets",
                    "summary": "List pets",
                    "parameters": [{"name": "limit", "in": "query", "schema": {"type": "integer"}}]
                  },
                  "post": {
                    "operationId": "createPet",
                    "requestBody": {"content": {"application/json": {
                      "schema": {"$ref": "#/components/schemas/NewPet"}}}}
                  }
                },
                "/pets/{petId}": {
                  "parameters": [{"name": "petId", "in": "path", "required": true,
                                  "schema": {"type": "string"}}],
                  "get": {"operationId": "getPet"}
                }
              }
            }
            """;

    @Test
    void parsesOperationsWithTheirParameters() {
        OpenApiCatalog.Parsed parsed = catalog.load(null, SPEC);

        assertThat(parsed.baseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(parsed.operations()).extracting(OpenApiCatalog.Operation::key)
                .containsExactlyInAnyOrder("GET /pets", "POST /pets", "GET /pets/{petId}");
    }

    @Test
    void pathLevelParametersApplyToEveryMethodUnderThePath() {
        var getPet = find("getPet");

        // Declared at path level, inherited by the operation — the common real-world layout.
        assertThat(getPet.params()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("petId");
            assertThat(p.in()).isEqualTo("path");
            assertThat(p.required()).isTrue();
        });
    }

    @Test
    void refBodiesAreResolvedIntoTheInputSchema() {
        var createPet = find("createPet");

        var schema = catalog.inputSchema(createPet);
        // The $ref was followed into components: the body property carries the real object schema,
        // not a dangling pointer the model cannot read.
        assertThat(schema.path("properties").path("body").path("properties").has("name")).isTrue();
        assertThat(schema.path("required").toString()).contains("body");
    }

    @Test
    void swagger2IsRefusedWithADirection() {
        assertThatThrownBy(() -> catalog.load(null, "{\"swagger\": \"2.0\", \"paths\": {}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Swagger 2.0");
    }

    @Test
    void yamlSpecsParseToo() {
        String yaml = """
                openapi: 3.0.0
                servers:
                  - url: https://y.example.com
                paths:
                  /ping:
                    get:
                      operationId: ping
                """;

        OpenApiCatalog.Parsed parsed = catalog.load(null, yaml);

        assertThat(parsed.operations()).singleElement()
                .satisfies(op -> assertThat(op.key()).isEqualTo("GET /ping"));
    }

    @Test
    void wildOperationIdsBecomeLegalToolNames() {
        // MCP tool names are [a-zA-Z0-9_-]; operationIds in the wild carry dots and spaces.
        assertThat(OpenApiCatalog.sanitize("Invoices.Create v2")).isEqualTo("Invoices_Create_v2");
    }

    private OpenApiCatalog.Operation find(String id) {
        return catalog.load(null, SPEC).operations().stream()
                .filter(op -> op.id().equals(id)).findFirst().orElseThrow();
    }
}
