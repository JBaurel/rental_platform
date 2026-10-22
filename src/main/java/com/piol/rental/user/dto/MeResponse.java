package com.piol.rental.user.dto;

import com.piol.rental.user.model.PlatformRole;

import java.util.UUID;

public record MeResponse(
        UUID id,
        String email,
        String phone,
        String fullName,
        PlatformRole platformRole,
        boolean emailVerified,
        boolean phoneVerified,
        List<SpaceRef> spaces
) {
}
