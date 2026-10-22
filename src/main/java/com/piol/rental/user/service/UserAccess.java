package com.piol.rental.user.service;

import com.piol.rental.user.model.AccountStatus;
import com.piol.rental.user.model.PlatformRole;

public record UserAccess(
        AccountStatus status,
        PlatformRole role
) {
}
