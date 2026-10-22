package com.piol.rental.user.repo;

import com.piol.rental.user.model.Owner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OwnerRepository extends JpaRepository<Owner, UUID> {
}
