package com.piol.rental.common.tenant;

import com.piol.rental.common.error.ApiException;
import com.piol.rental.user.model.MembershipRole;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Espace propriétaire courant de la requête (owner_id + rôle de l'utilisateur dans cet espace).
 * Renseigné par AccountContextFilter, effacé en fin de requête.
 * ThreadLocal => non propagé aux threads asynchrones / workers (à passer explicitement).
 */
public final class OwnerContext {
    public record Current(UUID ownerID, MembershipRole role){}

    private static final ThreadLocal<Current> HOLDER = new ThreadLocal<>();

    private OwnerContext(){

    }

    static void set(Current current){
        HOLDER.set(current);
    }

    static void clear(){
        HOLDER.remove();
    }

    public static Optional<Current> current(){
        return Optional.ofNullable(HOLDER.get());
    }

    public static Current require(){
        Current c = HOLDER.get();
        if(c == null){
            throw new ApiException(HttpStatus.BAD_REQUEST, "OWNER_REQUIRED", "Space for owner required");
        }
        return  c;
    }
}
