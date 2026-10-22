package com.piol.rental.user.repo;

import com.piol.rental.user.dto.SpaceRef;
import com.piol.rental.user.model.OwnerMembership;
import com.piol.rental.user.model.OwnerMembershipId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OwnerMembershipRepository extends JpaRepository<OwnerMembership, OwnerMembershipId> {

    @Query("select m.id.ownerId from OwnerMembership m where m.id.userId = :userId")
    List<UUID> findOwnerIdsByUserId(@Param("userId") UUID userId);

    @Query("""
            select new com.piol.rental.user.service.SpaceAccess(m.role, o.status)
            from OwnerMembership m, Owner o
            where m.id.userId = :userId and m.id.ownerId = :ownerId and o.id = m.id.ownerId
            """)
    Optional<SpaceAccess> findAccess(@Param("userId") UUID userId, @Param("ownerId") UUID ownerId);

    @Query("""
            select new com.piol.rental.user.dto.SpaceRef(m.id.ownerId, o.name, m.role)
            from OwnerMembership m, Owner o
            where m.id.userId = :userId and o.id = m.id.ownerId
            order by o.name
            """)
    List<SpaceRef> findSpaces(@Param("userId") UUID userId);
}
