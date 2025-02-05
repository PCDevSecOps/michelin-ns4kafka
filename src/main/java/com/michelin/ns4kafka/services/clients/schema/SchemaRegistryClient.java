package com.michelin.ns4kafka.services.clients.schema;

import com.michelin.ns4kafka.properties.ManagedClusterProperties;
import com.michelin.ns4kafka.services.clients.schema.entities.SchemaCompatibilityCheckResponse;
import com.michelin.ns4kafka.services.clients.schema.entities.SchemaCompatibilityRequest;
import com.michelin.ns4kafka.services.clients.schema.entities.SchemaCompatibilityResponse;
import com.michelin.ns4kafka.services.clients.schema.entities.SchemaRequest;
import com.michelin.ns4kafka.services.clients.schema.entities.SchemaResponse;
import com.michelin.ns4kafka.services.clients.schema.entities.TagEntities;
import com.michelin.ns4kafka.services.clients.schema.entities.TagInfo;
import com.michelin.ns4kafka.services.clients.schema.entities.TagTopicInfo;
import com.michelin.ns4kafka.utils.exceptions.ResourceValidationException;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Schema registry client.
 */
@Slf4j
@Singleton
public class SchemaRegistryClient {
    private static final String SUBJECTS = "/subjects/";
    private static final String CONFIG = "/config/";

    @Inject
    @Client(id = "schema-registry")
    private HttpClient httpClient;

    @Inject
    private List<ManagedClusterProperties> managedClusterProperties;

    /**
     * List subjects.
     *
     * @param kafkaCluster The Kafka cluster
     * @return A list of subjects
     */
    public Flux<String> getSubjects(String kafkaCluster) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest.GET(URI.create(StringUtils.prependUri(config.getUrl(), "/subjects")))
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Flux.from(httpClient.retrieve(request, String[].class)).flatMap(Flux::fromArray);
    }

    /**
     * Get a subject by it name and id.
     *
     * @param kafkaCluster The Kafka cluster
     * @param subject      The subject
     * @param version      The subject version
     * @return A subject
     */
    public Mono<SchemaResponse> getSubject(String kafkaCluster, String subject, String version) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest.GET(
                URI.create(StringUtils.prependUri(config.getUrl(), SUBJECTS + subject + "/versions/" + version)))
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, SchemaResponse.class))
            .onErrorResume(HttpClientResponseException.class,
                ex -> ex.getStatus().equals(HttpStatus.NOT_FOUND) ? Mono.empty() : Mono.error(ex));
    }

    /**
     * Get all the versions of a given subject.
     *
     * @param kafkaCluster The Kafka cluster
     * @param subject      The subject
     * @return All the versions of a subject
     */
    public Flux<SchemaResponse> getAllSubjectVersions(String kafkaCluster, String subject) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest.GET(
                URI.create(StringUtils.prependUri(config.getUrl(), SUBJECTS + subject + "/versions")))
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());

        return Flux.from(httpClient.retrieve(request, Integer[].class))
            .flatMap(ids -> Flux.fromIterable(Arrays.asList(ids))
                .flatMap(id -> {
                    HttpRequest<?> requestVersion = HttpRequest.GET(
                            URI.create(StringUtils.prependUri(config.getUrl(), SUBJECTS + subject + "/versions/" + id)))
                        .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());

                    return httpClient.retrieve(requestVersion, SchemaResponse.class);
                }))
            .onErrorResume(HttpClientResponseException.class,
                ex -> ex.getStatus().equals(HttpStatus.NOT_FOUND) ? Flux.empty() : Flux.error(ex));
    }

    /**
     * Register a subject and a schema.
     *
     * @param kafkaCluster The Kafka cluster
     * @param subject      The subject
     * @param body         The schema
     * @return The response of the registration
     */
    public Mono<SchemaResponse> register(String kafkaCluster, String subject, SchemaRequest body) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request =
            HttpRequest.POST(URI.create(StringUtils.prependUri(config.getUrl(), SUBJECTS + subject + "/versions")),
                    body)
                .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, SchemaResponse.class));
    }

    /**
     * Delete a subject.
     *
     * @param kafkaCluster The Kafka cluster
     * @param subject      The subject
     * @param hardDelete   Should the subject be hard deleted or not
     * @return The versions of the deleted subject
     */
    public Mono<Integer[]> deleteSubject(String kafkaCluster, String subject, boolean hardDelete) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        MutableHttpRequest<?> request = HttpRequest.DELETE(
                URI.create(StringUtils.prependUri(config.getUrl(), SUBJECTS + subject + "?permanent=" + hardDelete)))
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, Integer[].class));
    }

    /**
     * Validate the schema compatibility.
     *
     * @param kafkaCluster The Kafka cluster
     * @param subject      The subject
     * @param body         The request
     * @return The schema compatibility validation
     */
    public Mono<SchemaCompatibilityCheckResponse> validateSchemaCompatibility(String kafkaCluster, String subject,
                                                                              SchemaRequest body) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest.POST(URI.create(
                    StringUtils.prependUri(config.getUrl(), "/compatibility/subjects/" + subject
                        + "/versions?verbose=true")),
                body)
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, SchemaCompatibilityCheckResponse.class))
            .onErrorResume(HttpClientResponseException.class,
                ex -> ex.getStatus().equals(HttpStatus.NOT_FOUND) ? Mono.empty() : Mono.error(ex));
    }

    /**
     * Update the subject compatibility.
     *
     * @param kafkaCluster The Kafka cluster
     * @param subject      The subject
     * @param body         The schema compatibility request
     * @return The schema compatibility update
     */
    public Mono<SchemaCompatibilityResponse> updateSubjectCompatibility(String kafkaCluster, String subject,
                                                                        SchemaCompatibilityRequest body) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request =
            HttpRequest.PUT(URI.create(StringUtils.prependUri(config.getUrl(), CONFIG + subject)), body)
                .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, SchemaCompatibilityResponse.class));
    }

    /**
     * Get the current compatibility by subject.
     *
     * @param kafkaCluster The Kafka cluster
     * @param subject      The subject
     * @return The current schema compatibility
     */
    public Mono<SchemaCompatibilityResponse> getCurrentCompatibilityBySubject(String kafkaCluster, String subject) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest.GET(URI.create(StringUtils.prependUri(config.getUrl(), CONFIG + subject)))
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, SchemaCompatibilityResponse.class))
            .onErrorResume(HttpClientResponseException.class,
                ex -> ex.getStatus().equals(HttpStatus.NOT_FOUND) ? Mono.empty() : Mono.error(ex));
    }

    /**
     * Delete current compatibility by subject.
     *
     * @param kafkaCluster The Kafka cluster
     * @param subject      The subject
     * @return The deleted schema compatibility
     */
    public Mono<SchemaCompatibilityResponse> deleteCurrentCompatibilityBySubject(String kafkaCluster, String subject) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        MutableHttpRequest<?> request =
            HttpRequest.DELETE(URI.create(StringUtils.prependUri(config.getUrl(), CONFIG + subject)))
                .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, SchemaCompatibilityResponse.class));
    }

    /**
     * List tags.
     *
     * @param kafkaCluster The Kafka cluster
     * @return A list of tags
     */
    public Mono<List<TagInfo>> getTags(String kafkaCluster) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest
            .GET(URI.create(StringUtils.prependUri(
                config.getUrl(), "/catalog/v1/types/tagdefs")))
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, Argument.listOf(TagInfo.class)));
    }


    /**
     * List tags of a topic.
     *
     * @param kafkaCluster The Kafka cluster
     * @return A list of tags
     */
    public Mono<TagEntities> getTopicWithTags(String kafkaCluster) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest
            .GET(URI.create(StringUtils.prependUri(
                config.getUrl(), "/catalog/v1/search/basic?type=kafka_topic&tag=*")))
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, TagEntities.class));
    }

    /**
     * Add a tag to a topic.
     *
     * @param kafkaCluster The Kafka cluster
     * @param tagSpecs     Tags to add
     * @return Information about added tags
     */
    public Mono<List<TagTopicInfo>> associateTags(String kafkaCluster, List<TagTopicInfo> tagSpecs) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest
            .POST(URI.create(StringUtils.prependUri(
                config.getUrl(),
                "/catalog/v1/entity/tags")), tagSpecs)
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, Argument.listOf(TagTopicInfo.class)));
    }

    /**
     * Create tags.
     *
     * @param tags         The list of tags to create
     * @param kafkaCluster The Kafka cluster
     * @return Information about created tags
     */
    public Mono<List<TagInfo>> createTags(List<TagInfo> tags, String kafkaCluster) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest.POST(URI.create(StringUtils.prependUri(
                config.getUrl(), "/catalog/v1/types/tagdefs")), tags)
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.retrieve(request, Argument.listOf(TagInfo.class)));
    }

    /**
     * Delete a tag to a topic.
     *
     * @param kafkaCluster The Kafka cluster
     * @param entityName   The topic's name
     * @param tagName      The tag to delete
     * @return The resume response
     */
    public Mono<HttpResponse<Void>> dissociateTag(String kafkaCluster, String entityName, String tagName) {
        ManagedClusterProperties.SchemaRegistryProperties config = getSchemaRegistry(kafkaCluster);
        HttpRequest<?> request = HttpRequest
            .DELETE(URI.create(StringUtils.prependUri(
                config.getUrl(),
                "/catalog/v1/entity/type/kafka_topic/name/" + entityName + "/tags/" + tagName)))
            .basicAuth(config.getBasicAuthUsername(), config.getBasicAuthPassword());
        return Mono.from(httpClient.exchange(request, Void.class));
    }

    /**
     * Get the schema registry of the given Kafka cluster.
     *
     * @param kafkaCluster The Kafka cluster
     * @return The schema registry configuration
     */
    private ManagedClusterProperties.SchemaRegistryProperties getSchemaRegistry(String kafkaCluster) {
        Optional<ManagedClusterProperties> config = managedClusterProperties.stream()
            .filter(kafkaAsyncExecutorConfig -> kafkaAsyncExecutorConfig.getName().equals(kafkaCluster))
            .findFirst();

        if (config.isEmpty()) {
            throw new ResourceValidationException(null, null,
                List.of("Kafka Cluster [" + kafkaCluster + "] not found"));
        }

        if (config.get().getSchemaRegistry() == null) {
            throw new ResourceValidationException(null, null,
                List.of("Kafka Cluster [" + kafkaCluster + "] has no schema registry"));
        }

        return config.get().getSchemaRegistry();
    }
}
