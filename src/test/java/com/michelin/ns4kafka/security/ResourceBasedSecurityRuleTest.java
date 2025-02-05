package com.michelin.ns4kafka.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.michelin.ns4kafka.models.Metadata;
import com.michelin.ns4kafka.models.Namespace;
import com.michelin.ns4kafka.models.RoleBinding;
import com.michelin.ns4kafka.properties.SecurityProperties;
import com.michelin.ns4kafka.repositories.NamespaceRepository;
import com.michelin.ns4kafka.repositories.RoleBindingRepository;
import com.michelin.ns4kafka.utils.exceptions.ForbiddenNamespaceException;
import com.michelin.ns4kafka.utils.exceptions.UnknownNamespaceException;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRuleResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResourceBasedSecurityRuleTest {
    @Mock
    NamespaceRepository namespaceRepository;

    @Mock
    RoleBindingRepository roleBindingRepository;

    @Mock
    SecurityProperties securityProperties;

    @InjectMocks
    ResourceBasedSecurityRule resourceBasedSecurityRule;

    @Test
    void shouldReturnsUnknownWhenUnauthenticated() {
        SecurityRuleResult actual = resourceBasedSecurityRule.checkSecurity(HttpRequest.GET("/anything"), null);
        assertEquals(SecurityRuleResult.UNKNOWN, actual);
    }

    @Test
    void shouldReturnsUnknownWhenMissingClaims() {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups);
        Authentication auth = Authentication.build("user", claims);

        SecurityRuleResult actual = resourceBasedSecurityRule.checkSecurity(HttpRequest.GET("/anything"), auth);
        assertEquals(SecurityRuleResult.UNKNOWN, actual);
    }

    @ParameterizedTest
    @CsvSource({"/non-namespaced/resource", "/api/namespaces"})
    void shouldReturnsUnknownWhenInvalidResource(String path) {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        SecurityRuleResult actual =
            resourceBasedSecurityRule.checkSecurity(HttpRequest.GET(path), auth);
        assertEquals(SecurityRuleResult.UNKNOWN, actual);
    }

    @Test
    void shouldReturnUnknownNamespace() {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        when(namespaceRepository.findByName("test"))
            .thenReturn(Optional.empty());

        HttpRequest<?> request = HttpRequest.GET("/api/namespaces/test/connectors");

        UnknownNamespaceException exception = assertThrows(UnknownNamespaceException.class,
            () -> resourceBasedSecurityRule.checkSecurity(request, auth));

        assertEquals("Accessing unknown namespace \"test\"", exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"name$space", "*namespace*"})
    void shouldReturnUnknownWhenWrongNamespaceName(String namespace) {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        SecurityRuleResult actual =
            resourceBasedSecurityRule.checkSecurity(HttpRequest.GET("/api/namespaces/" + namespace + "/topics"), auth);

        assertEquals(SecurityRuleResult.UNKNOWN, actual);
    }

    @Test
    void shouldReturnUnknownNamespaceAsAdmin() {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of("isAdmin()"));
        Authentication auth = Authentication.build("user", List.of("isAdmin()"), claims);

        when(namespaceRepository.findByName("admin"))
            .thenReturn(Optional.empty());

        HttpRequest<?> request = HttpRequest.GET("/api/namespaces/admin/connectors");

        UnknownNamespaceException exception = assertThrows(UnknownNamespaceException.class,
            () -> resourceBasedSecurityRule.checkSecurity(request, auth));

        assertEquals("Accessing unknown namespace \"admin\"", exception.getMessage());
    }

    @Test
    void checkReturnsAllowedNamespaceAsAdmin() {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of("isAdmin()"));
        Authentication auth = Authentication.build("user", List.of("isAdmin()"), claims);

        when(namespaceRepository.findByName("test"))
            .thenReturn(Optional.of(Namespace.builder().build()));

        SecurityRuleResult actual =
            resourceBasedSecurityRule.checkSecurity(HttpRequest.GET("/api/namespaces/test/connectors"), auth);
        assertEquals(SecurityRuleResult.ALLOWED, actual);
    }

    @ParameterizedTest
    @CsvSource({"connectors,/api/namespaces/test/connectors",
        "role-bindings,/api/namespaces/test/role-bindings",
        "topics,/api/namespaces/test/topics/topic.with.dots"})
    void shouldReturnsAllowedWhenHyphenAndDotInResources(String resourceType, String path) {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        when(roleBindingRepository.findAllForGroups(groups))
            .thenReturn(List.of(RoleBinding.builder()
                .metadata(Metadata.builder().namespace("test")
                    .build())
                .spec(RoleBinding.RoleBindingSpec.builder()
                    .role(RoleBinding.Role.builder()
                        .resourceTypes(List.of(resourceType))
                        .verbs(List.of(RoleBinding.Verb.GET))
                        .build())
                    .subject(RoleBinding.Subject.builder().subjectName("group1")
                        .build())
                    .build())
                .build()));
        when(namespaceRepository.findByName("test"))
            .thenReturn(Optional.of(Namespace.builder().build()));

        SecurityRuleResult actual =
            resourceBasedSecurityRule.checkSecurity(HttpRequest.GET(path), auth);
        assertEquals(SecurityRuleResult.ALLOWED, actual);
    }

    @Test
    void shouldReturnsAllowedWhenSubresource() {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        when(roleBindingRepository.findAllForGroups(groups))
            .thenReturn(List.of(RoleBinding.builder()
                .metadata(Metadata.builder().namespace("test")
                    .build())
                .spec(RoleBinding.RoleBindingSpec.builder()
                    .role(RoleBinding.Role.builder()
                        .resourceTypes(List.of("connectors/restart", "topics/delete-records"))
                        .verbs(List.of(RoleBinding.Verb.GET))
                        .build())
                    .subject(RoleBinding.Subject.builder().subjectName("group1")
                        .build())
                    .build())
                .build()));
        when(namespaceRepository.findByName("test"))
            .thenReturn(Optional.of(Namespace.builder().build()));

        SecurityRuleResult actual =
            resourceBasedSecurityRule.checkSecurity(HttpRequest.GET("/api/namespaces/test/connectors/name/restart"),
                auth);
        assertEquals(SecurityRuleResult.ALLOWED, actual);

        actual =
            resourceBasedSecurityRule.checkSecurity(HttpRequest.GET("/api/namespaces/test/topics/name/delete-records"),
                auth);
        assertEquals(SecurityRuleResult.ALLOWED, actual);
    }

    @ParameterizedTest
    @CsvSource({"namespace", "name-space", "name.space", "_name_space_", "namespace123"})
    void shouldReturnsAllowedWhenNamespaceName(String namespace) {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        when(roleBindingRepository.findAllForGroups(groups))
            .thenReturn(List.of(RoleBinding.builder()
                .metadata(Metadata.builder()
                    .namespace(namespace)
                    .build())
                .spec(RoleBinding.RoleBindingSpec.builder()
                    .role(RoleBinding.Role.builder()
                        .resourceTypes(List.of("topics"))
                        .verbs(List.of(RoleBinding.Verb.GET))
                        .build())
                    .subject(RoleBinding.Subject.builder().subjectName("group1")
                        .build())
                    .build())
                .build()));

        when(namespaceRepository.findByName(namespace))
            .thenReturn(Optional.of(Namespace.builder().build()));

        SecurityRuleResult actual =
            resourceBasedSecurityRule.checkSecurity(HttpRequest.GET("/api/namespaces/" + namespace + "/topics"), auth);
        assertEquals(SecurityRuleResult.ALLOWED, actual);
    }

    @Test
    void shouldReturnsForbiddenNamespaceWhenNoRoleBinding() {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        when(namespaceRepository.findByName("test"))
            .thenReturn(Optional.of(Namespace.builder().build()));

        when(roleBindingRepository.findAllForGroups(groups))
            .thenReturn(List.of());

        HttpRequest<?> request = HttpRequest.GET("/api/namespaces/test/connectors");

        ForbiddenNamespaceException exception = assertThrows(ForbiddenNamespaceException.class,
            () -> resourceBasedSecurityRule.checkSecurity(request, auth));

        assertEquals("Accessing forbidden namespace \"test\"", exception.getMessage());
    }

    @Test
    void shouldReturnsForbiddenNamespaceWhenNoRoleBindingMatchingRequestedNamespace() {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        when(namespaceRepository.findByName("forbiddenNamespace"))
            .thenReturn(Optional.of(Namespace.builder().build()));

        when(roleBindingRepository.findAllForGroups(groups))
            .thenReturn(List.of(RoleBinding.builder()
                .metadata(Metadata.builder()
                    .namespace("test")
                    .build())
                .spec(RoleBinding.RoleBindingSpec.builder()
                    .role(RoleBinding.Role.builder()
                        .resourceTypes(List.of("connectors"))
                        .verbs(List.of(RoleBinding.Verb.GET))
                        .build())
                    .subject(RoleBinding.Subject.builder()
                        .subjectName("group1")
                        .build())
                    .build())
                .build()));

        HttpRequest<?> request = HttpRequest.GET("/api/namespaces/forbiddenNamespace/connectors");

        ForbiddenNamespaceException exception = assertThrows(ForbiddenNamespaceException.class,
            () -> resourceBasedSecurityRule.checkSecurity(request, auth));

        assertEquals("Accessing forbidden namespace \"forbiddenNamespace\"", exception.getMessage());
    }

    @Test
    void checkReturnsUnknownSubResource() {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        when(namespaceRepository.findByName("test"))
            .thenReturn(Optional.of(Namespace.builder().build()));
        when(roleBindingRepository.findAllForGroups(groups))
            .thenReturn(List.of(RoleBinding.builder()
                .metadata(Metadata.builder().namespace("test")
                    .build())
                .spec(RoleBinding.RoleBindingSpec.builder()
                    .role(RoleBinding.Role.builder()
                        .resourceTypes(List.of("connectors"))
                        .verbs(List.of(RoleBinding.Verb.GET))
                        .build())
                    .subject(RoleBinding.Subject.builder().subjectName("group1")
                        .build())
                    .build())
                .build()));

        SecurityRuleResult actual =
            resourceBasedSecurityRule.checkSecurity(HttpRequest.GET("/api/namespaces/test/connectors/name/restart"),
                auth);
        assertEquals(SecurityRuleResult.UNKNOWN, actual);
    }

    @Test
    void checkReturnsUnknownSubResourceWithDot() {
        List<String> groups = List.of("group1");
        Map<String, Object> claims = Map.of("sub", "user", "groups", groups, "roles", List.of());
        Authentication auth = Authentication.build("user", claims);

        when(namespaceRepository.findByName("test"))
            .thenReturn(Optional.of(Namespace.builder().build()));
        when(roleBindingRepository.findAllForGroups(groups))
            .thenReturn(List.of(RoleBinding.builder()
                .metadata(Metadata.builder().namespace("test")
                    .build())
                .spec(RoleBinding.RoleBindingSpec.builder()
                    .role(RoleBinding.Role.builder()
                        .resourceTypes(List.of("connectors"))
                        .verbs(List.of(RoleBinding.Verb.GET))
                        .build())
                    .subject(RoleBinding.Subject.builder().subjectName("group1")
                        .build())
                    .build())
                .build()));

        SecurityRuleResult actual = resourceBasedSecurityRule.checkSecurity(
            HttpRequest.GET("/api/namespaces/test/connectors/name.with.dots/restart"), auth);
        assertEquals(SecurityRuleResult.UNKNOWN, actual);
    }

    @Test
    void computeRolesNoAdmin() {
        when(securityProperties.getAdminGroup())
            .thenReturn("admin-group");
        List<String> actual = resourceBasedSecurityRule.computeRolesFromGroups(List.of("not-admin"));

        Assertions.assertIterableEquals(List.of(), actual);
    }

    @Test
    void computeRolesAdmin() {
        when(securityProperties.getAdminGroup())
            .thenReturn("admin-group");
        List<String> actual = resourceBasedSecurityRule.computeRolesFromGroups(List.of("admin-group"));

        Assertions.assertIterableEquals(List.of(ResourceBasedSecurityRule.IS_ADMIN), actual);
    }
}
