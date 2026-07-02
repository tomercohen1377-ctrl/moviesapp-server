package com.tcohen.moviesapp.server.auth

import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Issues and verifies short-lived bearer tokens (JWTs).
 *
 * Token shape:
 *   iss = "moviesapp-server"
 *   sub = userId
 *   iat = issued-at (epoch s)
 *   exp = issued-at + ttlSeconds (epoch s)
 *   alg = RS256 (RSA) — public key is exposed via /auth/jwks.json
 *
 * Key management:
 *   - Default (dev, tests): ephemeral RSA-2048 keypair generated on
 *     startup. Convenient for local iteration; tokens are invalidated
 *     whenever the JVM restarts.
 *   - Production (`JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY` set):
 *     base64url-encoded PKCS#8 / X.509 RSA keys loaded from env vars.
 *     Tokens survive restarts and are valid for `JWT_TTL_SECONDS`
 *     (default 24 h).
 */
@Service
class JwtService(
    @Value("\${jwt.issuer:moviesapp-server}") private val issuer: String,
    @Value("\${jwt.ttl-seconds:86400}") private val ttlSeconds: Long,
    @Value("\${jwt.private-key:}") private val privateKeyB64: String,
    @Value("\${jwt.public-key:}") private val publicKeyB64: String,
) {

    private val keyPair: KeyPair = loadOrGenerateKeyPair()

    /**
     * Sign a JWT for the given user. Caller is responsible for any
     * server-side checks (password verified, user exists).
     */
    fun issueToken(userId: String): String {
        val now = Instant.now()
        return Jwts.builder()
            .issuer(issuer)
            .subject(userId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)))
            .signWith(keyPair.private, Jwts.SIG.RS256)
            .compact()
    }

    /**
     * Returns the subject (userId) of a verified token, or null if the
     * token is missing, malformed, expired, or has a bad signature.
     */
    fun verify(token: String): String? = try {
        val parsed = Jwts.parser()
            .verifyWith(keyPair.public)
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token)
        parsed.payload.subject
    } catch (_: Exception) {
        null
    }

    /** Public key, JWK-flavored as plain base64url-encoded RSA modulus/exponent. */
    fun jwk(): Map<String, Any?> {
        val pub = keyPair.public as RSAPublicKey
        return mapOf(
            "kty" to "RSA",
            "alg" to "RS256",
            "use" to "sig",
            "kid" to "primary",
            "n" to Base64.getUrlEncoder().withoutPadding().encodeToString(pub.modulus.toByteArray()),
            "e" to Base64.getUrlEncoder().withoutPadding().encodeToString(pub.publicExponent.toByteArray()),
        )
    }

    private fun loadOrGenerateKeyPair(): KeyPair {
        if (privateKeyB64.isBlank() || publicKeyB64.isBlank()) {
            // Dev/test path — generate fresh on startup.
            val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            return gen.generateKeyPair()
        }
        val kf = KeyFactory.getInstance("RSA")
        val privRaw = Base64.getDecoder().decode(privateKeyB64)
        val pubRaw = Base64.getDecoder().decode(publicKeyB64)
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privRaw)) as RSAPrivateKey
        val pub = kf.generatePublic(X509EncodedKeySpec(pubRaw)) as RSAPublicKey
        return KeyPair(pub, priv)
    }
}
