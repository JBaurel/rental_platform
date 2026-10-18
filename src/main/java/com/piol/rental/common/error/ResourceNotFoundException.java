package com.piol.rental.common.error;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApiException{
    
    public ResourceNotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", resource + " introuvable : " + id);
    }
}
