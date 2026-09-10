package cl.campuslab.bff.web.dto;

import java.util.List;

public record MeResponse(String sub, String oid, String username, String email, List<String> roles, String issuer) {
}
