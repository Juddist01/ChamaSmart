package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a Firebase ID token (the proof of identity the browser gets after "Sign in with Google").
 *
 * What it checks, in plain words:
 *  1. The token is signed by Google (RS256, using Google's published public certificates).
 *  2. It was issued for THIS Firebase project (audience and issuer match the project id).
 *  3. It has not expired and was not issued in the future.
 *  4. It names a user (the "sub" claim).
 *
 * No secret key is needed: the project id is public and Google's certificates are public.
 */
@Singleton
public class FirebaseTokenVerifier {

    /** Thrown for any token that must not be trusted. The message is for logs, not for users. */
    public static class InvalidTokenException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidTokenException(String message) {
            super(message);
        }
    }

    /** The facts we use from a verified token. */
    public static class FirebaseUser {
        public final String uid;
        public final String email;          // may be null
        public final boolean emailVerified;
        public final String name;           // may be null
        public final String provider;       // e.g. "google.com" or "github.com"

        public FirebaseUser(String uid, String email, boolean emailVerified, String name, String provider) {
            this.uid = uid;
            this.email = email;
            this.emailVerified = emailVerified;
            this.name = name;
            this.provider = provider;
        }
    }

    /** Where the public signing keys come from. Replaced by a fake in tests. */
    public interface KeySource {
        PublicKey keyFor(String keyId) throws Exception;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOKEN_CHARS = 8192;
    private static final long FUTURE_ISSUE_LEEWAY_SECONDS = 300;
    private static final long EXPIRY_LEEWAY_SECONDS = 60;

    private final String projectId;
    private final KeySource keys;
    private final LongSupplier nowSeconds;

    @Inject
    public FirebaseTokenVerifier(Config config) {
        this(config.getString("firebase.projectId"),
                new GoogleKeySource(GoogleKeySource.DEFAULT_URL),
                () -> System.currentTimeMillis() / 1000L);
    }

    public FirebaseTokenVerifier(String projectId, KeySource keys, LongSupplier nowSeconds) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("firebase.projectId must be set");
        }
        this.projectId = projectId;
        this.keys = keys;
        this.nowSeconds = nowSeconds;
    }

    public FirebaseUser verify(String idToken) throws InvalidTokenException {
        if (idToken == null || idToken.isBlank() || idToken.length() > MAX_TOKEN_CHARS) {
            throw new InvalidTokenException("missing or oversized token");
        }
        String[] parts = idToken.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("token does not have three parts");
        }

        JsonNode header;
        JsonNode payload;
        try {
            header = MAPPER.readTree(decode(parts[0]));
            payload = MAPPER.readTree(decode(parts[1]));
        } catch (Exception e) {
            throw new InvalidTokenException("token is not valid base64/json");
        }

        // Only accept the algorithm Firebase uses. This blocks "alg: none" and similar tricks.
        if (!"RS256".equals(header.path("alg").asText(""))) {
            throw new InvalidTokenException("unsupported algorithm");
        }
        String keyId = header.path("kid").asText("");
        if (keyId.isEmpty()) {
            throw new InvalidTokenException("missing key id");
        }

        PublicKey key;
        try {
            key = keys.keyFor(keyId);
        } catch (Exception e) {
            throw new InvalidTokenException("signing keys unavailable: " + e.getMessage());
        }
        if (key == null) {
            throw new InvalidTokenException("unknown signing key");
        }

        boolean signatureOk;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            signatureOk = signature.verify(decode(parts[2]));
        } catch (Exception e) {
            signatureOk = false;
        }
        if (!signatureOk) {
            throw new InvalidTokenException("signature check failed");
        }

        long now = nowSeconds.getAsLong();
        if (!payload.path("exp").isNumber() || payload.path("exp").asLong() + EXPIRY_LEEWAY_SECONDS <= now) {
            throw new InvalidTokenException("token expired");
        }
        if (!payload.path("iat").isNumber() || payload.path("iat").asLong() > now + FUTURE_ISSUE_LEEWAY_SECONDS) {
            throw new InvalidTokenException("token issued in the future");
        }
        if (payload.has("auth_time") && payload.path("auth_time").asLong() > now + FUTURE_ISSUE_LEEWAY_SECONDS) {
            throw new InvalidTokenException("auth_time in the future");
        }
        if (!projectId.equals(payload.path("aud").asText(""))) {
            throw new InvalidTokenException("token is for a different project");
        }
        if (!("https://securetoken.google.com/" + projectId).equals(payload.path("iss").asText(""))) {
            throw new InvalidTokenException("wrong issuer");
        }
        String uid = payload.path("sub").asText("");
        if (uid.isEmpty() || uid.length() > 128) {
            throw new InvalidTokenException("missing subject");
        }

        String email = payload.path("email").asText(null);
        boolean emailVerified = payload.path("email_verified").asBoolean(false);
        String name = payload.path("name").asText(null);
        String provider = payload.path("firebase").path("sign_in_provider").asText(null);
        return new FirebaseUser(uid, email, emailVerified, name, provider);
    }

    private static byte[] decode(String base64Url) {
        return Base64.getUrlDecoder().decode(base64Url);
    }

    // ------------------------------------------------------------------------------------------
    // Google's public certificates (fetched over HTTPS and cached until Google says they expire)
    // ------------------------------------------------------------------------------------------
    public static final class GoogleKeySource implements KeySource {
        public static final String DEFAULT_URL =
                "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";
        private static final Pattern MAX_AGE = Pattern.compile("max-age=(\\d+)");
        private static final long MIN_REFETCH_MILLIS = 60_000L;

        private final String url;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private Map<String, PublicKey> cache = new HashMap<>();
        private long expiresAtMillis = 0;
        private long lastRefreshMillis = 0;

        public GoogleKeySource(String url) {
            this.url = url;
        }

        @Override
        public synchronized PublicKey keyFor(String keyId) throws Exception {
            long now = System.currentTimeMillis();
            if (now >= expiresAtMillis) {
                refreshOrKeepStale(now);
            }
            PublicKey key = cache.get(keyId);
            // Google rotates keys; if we do not know this one, look again (but not more than once a minute).
            if (key == null && now - lastRefreshMillis >= MIN_REFETCH_MILLIS) {
                refreshOrKeepStale(now);
                key = cache.get(keyId);
            }
            return key;
        }

        private void refreshOrKeepStale(long now) throws Exception {
            try {
                refresh(now);
            } catch (Exception e) {
                if (cache.isEmpty()) {
                    throw e;     // nothing to fall back on
                }
                expiresAtMillis = now + 60_000L;   // keep using the old keys, retry in a minute
            }
        }

        private void refresh(long now) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("certificate fetch returned HTTP " + response.statusCode());
            }
            Map<String, PublicKey> fresh = parseCertificates(response.body());
            long maxAgeSeconds = parseMaxAge(response.headers().firstValue("cache-control").orElse(""));
            cache = fresh;
            lastRefreshMillis = now;
            expiresAtMillis = now + Math.max(60L, Math.min(maxAgeSeconds, 86_400L)) * 1000L;
        }

        static Map<String, PublicKey> parseCertificates(String json) throws Exception {
            JsonNode root = MAPPER.readTree(json);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Map<String, PublicKey> result = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                byte[] pem = field.getValue().asText("").getBytes(StandardCharsets.UTF_8);
                X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(pem));
                result.put(field.getKey(), cert.getPublicKey());
            }
            if (result.isEmpty()) {
                throw new IllegalStateException("no certificates in response");
            }
            return result;
        }

        static long parseMaxAge(String cacheControl) {
            Matcher m = MAX_AGE.matcher(cacheControl == null ? "" : cacheControl);
            return m.find() ? Long.parseLong(m.group(1)) : 3600L;
        }
    }
}
