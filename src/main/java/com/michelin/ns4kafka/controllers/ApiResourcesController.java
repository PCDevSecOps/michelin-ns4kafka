package com.michelin.ns4kafka.controllers;

import com.michelin.ns4kafka.models.RoleBinding;
import com.michelin.ns4kafka.repositories.RoleBindingRepository;
import com.michelin.ns4kafka.security.ResourceBasedSecurityRule;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Controller to manage API resources.
 */
@Tag(name = "Resources", description = "Manage the API resources.")
@RolesAllowed(SecurityRule.IS_ANONYMOUS)
@Controller("/api-resources")
public class ApiResourcesController {
    /**
     * ACL resource definition.
     */
    public static final ResourceDefinition ACL = ResourceDefinition.builder()
        .kind("AccessControlEntry")
        .namespaced(true)
        .synchronizable(false)
        .path("acls")
        .names(List.of("acls", "acl", "ac"))
        .build();

    /**
     * Connector resource definition.
     */
    public static final ResourceDefinition CONNECTOR = ResourceDefinition.builder()
        .kind("Connector")
        .namespaced(true)
        .synchronizable(true)
        .path("connectors")
        .names(List.of("connects", "connect", "co"))
        .build();

    /**
     * Kafka Streams resource definition.
     */
    public static final ResourceDefinition KSTREAM = ResourceDefinition.builder()
        .kind("KafkaStream")
        .namespaced(true)
        .synchronizable(false)
        .path("streams")
        .names(List.of("streams", "stream", "st"))
        .build();

    /**
     * Role binding resource definition.
     */
    public static final ResourceDefinition ROLE_BINDING = ResourceDefinition.builder()
        .kind("RoleBinding")
        .namespaced(true)
        .synchronizable(false)
        .path("role-bindings")
        .names(List.of("rolebindings", "rolebinding", "rb"))
        .build();

    /**
     * Topic resource definition.
     */
    public static final ResourceDefinition TOPIC = ResourceDefinition.builder()
        .kind("Topic")
        .namespaced(true)
        .synchronizable(true)
        .path("topics")
        .names(List.of("topics", "topic", "to"))
        .build();

    /**
     * Schema resource definition.
     */
    public static final ResourceDefinition SCHEMA = ResourceDefinition.builder()
        .kind("Schema")
        .namespaced(true)
        .synchronizable(false)
        .path("schemas")
        .names(List.of("schemas", "schema", "sc"))
        .build();

    /**
     * Resource quota resource definition.
     */
    public static final ResourceDefinition RESOURCE_QUOTA = ResourceDefinition.builder()
        .kind("ResourceQuota")
        .namespaced(true)
        .synchronizable(false)
        .path("resource-quotas")
        .names(List.of("resource-quotas", "resource-quota", "quotas", "quota", "qu"))
        .build();

    /**
     * Connect worker resource definition.
     */
    public static final ResourceDefinition CONNECT_CLUSTER = ResourceDefinition.builder()
        .kind("ConnectCluster")
        .namespaced(true)
        .synchronizable(false)
        .path("connect-clusters")
        .names(List.of("connect-clusters", "connect-cluster", "cc"))
        .build();

    /**
     * Namespace resource definition.
     */
    public static final ResourceDefinition NAMESPACE = ResourceDefinition.builder()
        .kind("Namespace")
        .namespaced(false)
        .synchronizable(false)
        .path("namespaces")
        .names(List.of("namespaces", "namespace", "ns"))
        .build();

    /**
     * Role binding repository.
     */
    @Inject
    RoleBindingRepository roleBindingRepository;

    /**
     * List API resources.
     *
     * @param authentication The authentication
     * @return The list of API resources
     */
    @Get
    public List<ResourceDefinition> list(@Nullable Authentication authentication) {
        List<ResourceDefinition> all = List.of(
            ACL,
            CONNECTOR,
            KSTREAM,
            ROLE_BINDING,
            RESOURCE_QUOTA,
            CONNECT_CLUSTER,
            TOPIC,
            NAMESPACE,
            SCHEMA
        );

        if (authentication == null) {
            return all; // Backward compatibility for cli <= 1.3.0
        }

        List<String> roles = (List<String>) authentication.getAttributes().getOrDefault("roles", List.of());
        List<String> groups = (List<String>) authentication.getAttributes().getOrDefault("groups", List.of());

        if (roles.contains(ResourceBasedSecurityRule.IS_ADMIN)) {
            return all;
        }

        Collection<RoleBinding> roleBindings = roleBindingRepository.findAllForGroups(groups);
        List<String> authorizedResources = roleBindings.stream()
            .flatMap(roleBinding -> roleBinding.getSpec().getRole().getResourceTypes().stream())
            .distinct()
            .toList();

        return all.stream()
            .filter(resourceDefinition -> authorizedResources.contains(resourceDefinition.getPath()))
            .toList();
    }

    /**
     * API resource definition.
     */
    @Introspected
    @Builder
    @Getter
    @Setter
    public static class ResourceDefinition {
        private String kind;
        private boolean namespaced;
        private boolean synchronizable;
        private String path;
        private List<String> names;
    }
}
