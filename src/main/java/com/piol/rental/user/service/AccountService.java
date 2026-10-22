package com.piol.rental.user.service;

import com.piol.rental.common.error.ResourceNotFoundException;
import com.piol.rental.user.dto.MeResponse;
import com.piol.rental.user.model.AppUser;
import com.piol.rental.user.repo.AppUserRepository;
import com.piol.rental.user.repo.OwnerMembershipRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountService {
    private final AppUserRepository users;
    private final OwnerMembershipRepository memberships;

    public AccountService(AppUserRepository users, OwnerMembershipRepository memberships){
        this.users = users;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId){
        AppUser u = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User ", userId));

        return new MeResponse(
                u.getId(), u.getEmail(), u.getPhone(), u.getFullName(),
                u.getPlatformRole(), u.getEmailVerifiedAt() != null, u.getPhoneVerifiedAt() != null,
                memberships.findSpaces(userId)
        );
    }
}
