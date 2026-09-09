package cl.campuslab.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import cl.campuslab.catalog.domain.ResourceType;
import cl.campuslab.catalog.service.CatalogResourceService;
import cl.campuslab.catalog.service.MalformedResourceIdException;
import cl.campuslab.catalog.web.dto.CatalogResourceResponse;
import cl.campuslab.catalog.web.dto.CreateCatalogResourceRequest;
import cl.campuslab.catalog.web.dto.UpdateCatalogResourceRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Role enforcement itself is exercised end to end in
 * cl.campuslab.catalog.security.SecurityIntegrationTest; this test only covers the
 * controller's own response-building logic (Location header, id parsing) once a
 * caller has already passed that gate.
 */
@ExtendWith(MockitoExtension.class)
class CatalogResourceControllerTest {

    @Mock
    private CatalogResourceService service;

    @Test
    void create_returns201WithLocationHeaderPointingAtCreatedResource() {
        CatalogResourceController controller = new CatalogResourceController(service);
        UUID id = UUID.randomUUID();
        CreateCatalogResourceRequest request = new CreateCatalogResourceRequest(
                ResourceType.LABORATORIO, "Lab", null, null, null, 20);
        CatalogResourceResponse response = response(id, ResourceType.LABORATORIO, 0L);
        given(service.create(eq(request), any())).willReturn(response);

        ResponseEntity<CatalogResourceResponse> result = controller.create(request, adminAuthentication());

        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getHeaders().getLocation()).hasToString("/api/catalog/resources/" + id);
        assertThat(result.getBody()).isEqualTo(response);
    }

    @Test
    void update_withMalformedId_throwsBeforeCallingService() {
        CatalogResourceController controller = new CatalogResourceController(service);
        UpdateCatalogResourceRequest request = new UpdateCatalogResourceRequest("Lab", null, null, null, 18, 0L);

        assertThatThrownBy(() -> controller.update("not-a-uuid", request, adminAuthentication()))
                .isInstanceOf(MalformedResourceIdException.class);
    }

    @Test
    void update_withValidId_delegatesToServiceWithParsedUuid() {
        CatalogResourceController controller = new CatalogResourceController(service);
        UUID id = UUID.randomUUID();
        UpdateCatalogResourceRequest request = new UpdateCatalogResourceRequest("Lab", null, null, null, 18, 0L);
        CatalogResourceResponse response = response(id, ResourceType.LABORATORIO, 1L);
        given(service.update(eq(id), eq(request), any())).willReturn(response);

        CatalogResourceResponse result = controller.update(id.toString(), request, adminAuthentication());

        assertThat(result).isEqualTo(response);
    }

    @Test
    void list_delegatesToService() {
        CatalogResourceController controller = new CatalogResourceController(service);
        CatalogResourceResponse response = response(UUID.randomUUID(), ResourceType.EQUIPO, 0L);
        given(service.list()).willReturn(List.of(response));

        List<CatalogResourceResponse> result = controller.list();

        assertThat(result).containsExactly(response);
    }

    private static JwtAuthenticationToken adminAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("admin-uuid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static CatalogResourceResponse response(UUID id, ResourceType type, Long version) {
        Instant now = Instant.now();
        return new CatalogResourceResponse(id, type, "Lab", null, null, null, 20, version, now, now);
    }
}
