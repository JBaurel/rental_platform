package com.piol.rental.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Signature/vérification des JWT (HS256) et hachage des mots de passe. */
@Configuration
public class AuthBeansConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthBeansConfig.class);

    @Bean
    SecretKey jwtSecretKey(JwtProperties props) throws IllegalAccessException {
        byte[] bytes = props.secret() == null ? new byte[0] : props.secret().getBytes(StandardCharsets.UTF_8);

        if(bytes.length < 32){
            throw new IllegalAccessException("app.jwt must contain at least 32 caracters. (256 Bytes)");
        }
        if(props.secret().startsWith("dev-only")){
            log.warn("Secret JWT dev used: In Prod must be a new JWT_Secret defined");
        }

        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey){
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties props){
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }
    @Bean
    PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder(12);
    }
}
