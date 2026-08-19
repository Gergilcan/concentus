package com.concentus.api;

import com.concentus.config.AgentSpec.ApiSourceSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * One endpoint typed by hand, turned into the same operation a specification would have produced.
 *
 * <p>Sameness is the point: everything downstream — the tool schema, the required-argument check,
 * the placeholder encoding, the credential header — is the OpenAPI path, unchanged. So these pin
 * down the translation, and the rest is already covered where it lives.
 */
class PlainEndpointTest {

    private static ApiSourceSpec endpoint(String url, String method) {
        ApiSourceSpec spec = new ApiSourceSpec();
        spec.mode = "endpoint";
        spec.url = url;
        spec.method = method;
        return spec;
    }

    @Test
    void splits_a_url_into_the_base_and_the_path() {
        PlainEndpoint.Resolved resolved = PlainEndpoint.of(
                endpoint("https://api.example.com/v1/things", "POST"));

        assertThat(resolved.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(resolved.operation().path()).isEqualTo("/v1/things");
        assertThat(resolved.operation().method()).isEqualTo("POST");
    }

    @Test
    void keeps_the_port_with_the_base_url() {
        PlainEndpoint.Resolved resolved = PlainEndpoint.of(
                endpoint("http://localhost:8080/hooks/deploy", "POST"));

        assertThat(resolved.baseUrl()).isEqualTo("http://localhost:8080");
        assertThat(resolved.operation().path()).isEqualTo("/hooks/deploy");
    }

    // The placeholders are exactly what makes an endpoint worth exposing as a tool rather than
    // calling once — they become the arguments the agent fills in.
    @Test
    void every_placeholder_becomes_a_required_argument() {
        PlainEndpoint.Resolved resolved = PlainEndpoint.of(
                endpoint("https://api.example.com/orgs/{org}/repos/{repo}", "GET"));

        assertThat(resolved.operation().params())
                .extracting(OpenApiCatalog.Param::name)
                .containsExactly("org", "repo");
        assertThat(resolved.operation().params()).allMatch(OpenApiCatalog.Param::required);
        assertThat(resolved.operation().path()).isEqualTo("/orgs/{org}/repos/{repo}");
    }

    // ApiCaller treats a declared body as required, so declaring one on a GET would make the tool
    // impossible to call correctly.
    @Test
    void a_body_is_declared_only_where_one_can_be_sent() {
        ApiSourceSpec get = endpoint("https://api.example.com/things", "GET");
        get.sendsBody = true;
        ApiSourceSpec post = endpoint("https://api.example.com/things", "POST");
        post.sendsBody = true;
        ApiSourceSpec quiet = endpoint("https://api.example.com/things", "POST");

        assertThat(PlainEndpoint.of(get).operation().bodySchema()).isNull();
        assertThat(PlainEndpoint.of(post).operation().bodySchema()).isNotNull();
        assertThat(PlainEndpoint.of(quiet).operation().bodySchema()).isNull();
    }

    // With no specification there is nothing else for the model to read, so the node's own
    // sentence is the description — and something honest stands in when it is missing.
    @Test
    void the_description_is_the_nodes_own_words_or_the_call_itself() {
        ApiSourceSpec told = endpoint("https://api.example.com/things", "GET");
        told.description = "  Lists the things.  ";

        assertThat(PlainEndpoint.of(told).operation().description()).isEqualTo("Lists the things.");
        assertThat(PlainEndpoint.of(endpoint("https://api.example.com/things", "GET"))
                .operation().description()).isEqualTo("GET /things on api.example.com");
    }

    @Test
    void a_url_with_no_path_still_calls_the_root() {
        assertThat(PlainEndpoint.of(endpoint("https://api.example.com", "GET")).operation().path())
                .isEqualTo("/");
    }

    @Test
    void a_url_without_a_scheme_says_what_is_missing() {
        Throwable thrown = catchThrowable(() -> PlainEndpoint.of(endpoint("api.example.com/x", "GET")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void a_node_with_no_url_says_so_rather_than_calling_nothing() {
        Throwable thrown = catchThrowable(() -> PlainEndpoint.of(endpoint("   ", "GET")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no URL");
    }

    @Test
    void an_unknown_method_is_refused_with_the_ones_that_work() {
        Throwable thrown = catchThrowable(() ->
                PlainEndpoint.of(endpoint("https://api.example.com/x", "FETCH")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FETCH").hasMessageContaining("POST");
    }
}
