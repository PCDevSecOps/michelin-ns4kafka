package com.michelin.ns4kafka.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.michelin.ns4kafka.models.Metadata;
import com.michelin.ns4kafka.models.RoleBinding;
import com.michelin.ns4kafka.properties.SecurityProperties;
import com.michelin.ns4kafka.security.gitlab.GitlabAuthenticationProvider;
import com.michelin.ns4kafka.security.gitlab.GitlabAuthenticationService;
import com.michelin.ns4kafka.services.RoleBindingService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.security.authentication.AuthenticationException;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.authentication.UsernamePasswordCredentials;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GitlabAuthenticationProviderTest {
    @Mock
    GitlabAuthenticationService gitlabAuthenticationService;

    @Mock
    ResourceBasedSecurityRule resourceBasedSecurityRule;

    @Mock
    RoleBindingService roleBindingService;

    @Mock
    SecurityProperties securityProperties;

    @InjectMocks
    GitlabAuthenticationProvider gitlabAuthenticationProvider;

    @Test
    void authenticationSuccess() {
        AuthenticationRequest<String, String> authenticationRequest =
            new UsernamePasswordCredentials("username", "53cu23d_70k3n");

        List<String> groups = List.of("group-1", "group-2");

        RoleBinding roleBinding = RoleBinding.builder()
            .metadata(Metadata.builder()
                .name("namespace-rb1")
                .cluster("local")
                .build())
            .spec(RoleBinding.RoleBindingSpec.builder()
                .subject(RoleBinding.Subject.builder()
                    .subjectName("group-1")
                    .subjectType(RoleBinding.SubjectType.GROUP)
                    .build())
                .build())
            .build();

        when(gitlabAuthenticationService.findUsername(authenticationRequest.getSecret()))
            .thenReturn(Mono.just("email"));
        when(gitlabAuthenticationService.findAllGroups(authenticationRequest.getSecret()))
            .thenReturn(Flux.fromIterable(groups));
        when(roleBindingService.listByGroups(groups))
            .thenReturn(List.of(roleBinding));
        when(resourceBasedSecurityRule.computeRolesFromGroups(groups))
            .thenReturn(List.of());

        Publisher<AuthenticationResponse> authenticationResponsePublisher =
            gitlabAuthenticationProvider.authenticate(null, authenticationRequest);

        StepVerifier.create(authenticationResponsePublisher)
            .consumeNextWith(response -> {
                assertTrue(response.isAuthenticated());
                assertTrue(response.getAuthentication().isPresent());
                assertEquals("email", response.getAuthentication().get().getName());
                assertIterableEquals(groups,
                    (List<String>) response.getAuthentication().get().getAttributes().get("groups"));
                assertIterableEquals(List.of(), response.getAuthentication().get().getRoles(),
                    "User has no custom roles");
            })
            .verifyComplete();
    }

    @Test
    void authenticationSuccessAdmin() {
        AuthenticationRequest<String, String> authenticationRequest =
            new UsernamePasswordCredentials("admin", "53cu23d_70k3n");

        List<String> groups = List.of("group-1", "group-2", "group-admin");
        when(gitlabAuthenticationService.findUsername(authenticationRequest.getSecret()))
            .thenReturn(Mono.just("email"));
        when(gitlabAuthenticationService.findAllGroups(authenticationRequest.getSecret()))
            .thenReturn(Flux.fromIterable(groups));
        when(roleBindingService.listByGroups(groups))
            .thenReturn(List.of());
        when(securityProperties.getAdminGroup())
            .thenReturn("group-admin");
        when(resourceBasedSecurityRule.computeRolesFromGroups(groups))
            .thenReturn(List.of(ResourceBasedSecurityRule.IS_ADMIN));

        Publisher<AuthenticationResponse> authenticationResponsePublisher =
            gitlabAuthenticationProvider.authenticate(null, authenticationRequest);

        StepVerifier.create(authenticationResponsePublisher)
            .consumeNextWith(response -> {
                assertTrue(response.isAuthenticated());
                assertTrue(response.getAuthentication().isPresent());
                assertEquals("email", response.getAuthentication().get().getName());
                assertIterableEquals(groups,
                    (List<String>) response.getAuthentication().get().getAttributes().get("groups"));
                assertIterableEquals(List.of(ResourceBasedSecurityRule.IS_ADMIN),
                    response.getAuthentication().get().getRoles(), "User has custom roles");
            })
            .verifyComplete();
    }

    @Test
    void authenticationFailure() {
        AuthenticationRequest<String, String> authenticationRequest =
            new UsernamePasswordCredentials("admin", "f4k3_70k3n");

        when(gitlabAuthenticationService.findUsername(authenticationRequest.getSecret()))
            .thenReturn(Mono.error(new HttpClientResponseException("403 Unauthorized", HttpResponse.unauthorized())));

        Publisher<AuthenticationResponse> authenticationResponsePublisher =
            gitlabAuthenticationProvider.authenticate(null, authenticationRequest);

        StepVerifier.create(authenticationResponsePublisher)
            .consumeErrorWith(error -> assertEquals(AuthenticationException.class, error.getClass()))
            .verify();
    }

    @Test
    void authenticationFailureGroupsNotFound() {
        AuthenticationRequest<String, String> authenticationRequest =
            new UsernamePasswordCredentials("admin", "f4k3_70k3n");

        List<String> groups = List.of("group-1", "group-2");
        when(gitlabAuthenticationService.findUsername(authenticationRequest.getSecret()))
            .thenReturn(Mono.just("email"));
        when(gitlabAuthenticationService.findAllGroups(authenticationRequest.getSecret()))
            .thenReturn(Flux.fromIterable(groups));
        when(roleBindingService.listByGroups(groups))
            .thenReturn(List.of());
        when(securityProperties.getAdminGroup())
            .thenReturn("group-admin");

        Publisher<AuthenticationResponse> authenticationResponsePublisher =
            gitlabAuthenticationProvider.authenticate(null, authenticationRequest);

        StepVerifier.create(authenticationResponsePublisher)
            .consumeErrorWith(error -> assertEquals(AuthenticationException.class, error.getClass()))
            .verify();
    }
}
