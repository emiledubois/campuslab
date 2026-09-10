package cl.campuslab.bff.web.dto;

import java.util.List;

public record AdminPingResponse(String scope, String oid, List<String> roles) {
}
