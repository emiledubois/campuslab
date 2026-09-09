package cl.campuslab.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import cl.campuslab.catalog.domain.CatalogResource;
import cl.campuslab.catalog.domain.CatalogResourceRepository;
import cl.campuslab.catalog.domain.ResourceType;
import cl.campuslab.catalog.web.dto.CatalogResourceResponse;
import cl.campuslab.catalog.web.dto.CreateCatalogResourceRequest;
import cl.campuslab.catalog.web.dto.UpdateCatalogResourceRequest;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class CatalogResourceServiceTest {

    @Mock
    private CatalogResourceRepository repository;

    private CatalogResourceService service;

    @BeforeEach
    void setUp() {
        service = new CatalogResourceService(repository);
    }

    @Test
    void create_withValidLaboratorioRequest_savesAndReturnsResponse() {
        CreateCatalogResourceRequest request = new CreateCatalogResourceRequest(
                ResourceType.LABORATORIO, "Laboratorio de Redes 3", "20 estaciones", "Edificio C", null, 20);
        CatalogResource saved = withId(new CatalogResource(
                ResourceType.LABORATORIO, request.name(), request.description(), request.location(), null, 20));
        given(repository.save(any(CatalogResource.class))).willReturn(saved);

        CatalogResourceResponse response = service.create(request, adminAuthentication());

        assertThat(response.resourceType()).isEqualTo(ResourceType.LABORATORIO);
        assertThat(response.cupo()).isEqualTo(20);
        assertThat(response.stock()).isNull();
    }

    @Test
    void create_withLaboratorioAndStockPresent_throwsInvalidShape() {
        CreateCatalogResourceRequest request = new CreateCatalogResourceRequest(
                ResourceType.LABORATORIO, "Lab", null, null, 5, 20);

        assertThatThrownBy(() -> service.create(request, adminAuthentication()))
                .isInstanceOf(InvalidResourceShapeException.class);
    }

    @Test
    void create_withEquipoMissingStock_throwsInvalidShape() {
        CreateCatalogResourceRequest request = new CreateCatalogResourceRequest(
                ResourceType.EQUIPO, "Microscopio", null, null, null, null);

        assertThatThrownBy(() -> service.create(request, adminAuthentication()))
                .isInstanceOf(InvalidResourceShapeException.class);
    }

    @Test
    void create_withEquipoAndCupoPresent_throwsInvalidShape() {
        CreateCatalogResourceRequest request = new CreateCatalogResourceRequest(
                ResourceType.EQUIPO, "Microscopio", null, null, 5, 3);

        assertThatThrownBy(() -> service.create(request, adminAuthentication()))
                .isInstanceOf(InvalidResourceShapeException.class);
    }

    @Test
    void update_withMatchingVersion_appliesUpdateAndSaves() {
        UUID id = UUID.randomUUID();
        CatalogResource existing = withId(new CatalogResource(
                ResourceType.LABORATORIO, "Lab", null, null, null, 20));
        setVersion(existing, 0L);
        given(repository.findById(id)).willReturn(Optional.of(existing));
        given(repository.saveAndFlush(existing)).willReturn(existing);

        UpdateCatalogResourceRequest request = new UpdateCatalogResourceRequest(
                "Lab renovado", "desc", "loc", null, 18, 0L);

        CatalogResourceResponse response = service.update(id, request, adminAuthentication());

        assertThat(response.name()).isEqualTo("Lab renovado");
        assertThat(response.cupo()).isEqualTo(18);
        verify(repository).saveAndFlush(existing);
    }

    @Test
    void update_withStaleVersion_throwsOptimisticLockingFailure() {
        UUID id = UUID.randomUUID();
        CatalogResource existing = withId(new CatalogResource(
                ResourceType.LABORATORIO, "Lab", null, null, null, 20));
        setVersion(existing, 1L);
        given(repository.findById(id)).willReturn(Optional.of(existing));

        UpdateCatalogResourceRequest request = new UpdateCatalogResourceRequest(
                "Lab renovado", null, null, null, 18, 0L);

        assertThatThrownBy(() -> service.update(id, request, adminAuthentication()))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void update_withUnknownId_throwsResourceNotFound() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());

        UpdateCatalogResourceRequest request = new UpdateCatalogResourceRequest(
                "Lab renovado", null, null, null, 18, 0L);

        assertThatThrownBy(() -> service.update(id, request, adminAuthentication()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_withShapeViolationAgainstExistingType_throwsInvalidShape() {
        UUID id = UUID.randomUUID();
        CatalogResource existing = withId(new CatalogResource(
                ResourceType.EQUIPO, "Microscopio", null, null, 5, null));
        setVersion(existing, 0L);
        given(repository.findById(id)).willReturn(Optional.of(existing));

        UpdateCatalogResourceRequest request = new UpdateCatalogResourceRequest(
                "Microscopio", null, null, null, 3, 0L);

        assertThatThrownBy(() -> service.update(id, request, adminAuthentication()))
                .isInstanceOf(InvalidResourceShapeException.class);
    }

    @Test
    void list_returnsAllResourcesMappedToResponses() {
        CatalogResource resource = withId(new CatalogResource(
                ResourceType.INSUMO, "Guantes", null, null, 100, null));
        given(repository.findAll()).willReturn(List.of(resource));

        List<CatalogResourceResponse> responses = service.list();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).stock()).isEqualTo(100);
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

    /** Test-only reflection helpers - CatalogResource's id/version are Hibernate-managed and have no public setter. */
    private static CatalogResource withId(CatalogResource resource) {
        setField(resource, "id", UUID.randomUUID());
        return resource;
    }

    private static void setVersion(CatalogResource resource, Long version) {
        setField(resource, "version", version);
    }

    private static void setField(CatalogResource resource, String fieldName, Object value) {
        try {
            Field field = CatalogResource.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(resource, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
