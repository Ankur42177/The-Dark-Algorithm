package com.pulse.content.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RS256 signing keys for JWTs.
 *
 * <p><b>Asymmetric</b> signing: content-service SIGNS with the private key; anyone with the PUBLIC key
 * (the api-gateway in Phase 5, via GET /.well-known/jwks.json) can VERIFY tokens but can never create
 * them. With a shared secret (HS256), every verifier could also forge tokens.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    @Bean
    RSAKey rsaKey(AuthProperties props) throws Exception {
        RSAPublicKey pub;
        RSAPrivateKey priv;
        if (props.jwtPrivateKey() == null || props.jwtPrivateKey().isBlank()) {
            // DEV ONLY: random keys at every start -> tokens die on restart, and replicas would disagree.
            log.warn("PULSE_JWT_PRIVATE_KEY not set: generating a temporary RSA key pair (dev only). Run scripts/gen-jwt-keys.sh");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            pub = (RSAPublicKey) kp.getPublic();
            priv = (RSAPrivateKey) kp.getPrivate();
        } else {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(props.jwtPrivateKey().trim())));
            pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(props.jwtPublicKey().trim())));
        }
        // "kid" (key id) lets verifiers pick the right key after a key ROTATION (old + new key published together).
        return new RSAKey.Builder(pub).privateKey(priv).keyIDFromThumbprint().build();
    }

    /** The public part, served at /.well-known/jwks.json. */
    @Bean
    JWKSet publicJwkSet(RSAKey rsaKey) {
        return new JWKSet(rsaKey.toPublicJWK());
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey rsaKey) {
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(source);
    }

    /** Verifies signature, expiry ("exp"/"nbf" with 60 s clock skew) and the issuer. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey rsaKey, AuthProperties props) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }
}
