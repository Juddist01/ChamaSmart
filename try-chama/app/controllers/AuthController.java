package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import repositories.UserRepository;
import services.FirebaseTokenVerifier;
import services.FirebaseTokenVerifier.FirebaseUser;
import services.FirebaseTokenVerifier.InvalidTokenException;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Sign in with Google / GitHub".
 *
 * The browser signs in with Firebase and sends us the resulting ID token. We verify the token on the
 * server, find (or create) the matching row in the MySQL user table, and start the SAME session cookie
 * that the email/password sign-in starts. Everything after sign-in (dashboards, roles) is unchanged.
 */
public class AuthController extends Controller {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Marks accounts that have no usable password (they sign in through Firebase only). */
    public static final String SOCIAL_PASSWORD_PREFIX = "!social!";

    /** Roles a person may choose for themselves. "platform_admin" can never be self-assigned. */
    public static final Set<String> SELF_SERVICE_ROLES = Set.of("member", "chama_admin");

    private final UserRepository userRepository;
    private final FirebaseTokenVerifier verifier;

    @Inject
    public AuthController(UserRepository userRepository, FirebaseTokenVerifier verifier) {
        this.userRepository = userRepository;
        this.verifier = verifier;
    }

    // POST /api/auth/firebase
    // Body: { "idToken": "...", "role": "member|chama_admin", "chamaName": "...", "phoneNo": "...", "acceptTerms": true }
    // "role", "chamaName", "phoneNo" and "acceptTerms" are only needed when creating a NEW account (Sign Up page).
    public Result firebaseLogin(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }

        String idToken = json.path("idToken").asText(null);
        FirebaseUser fbUser;
        try {
            fbUser = verifier.verify(idToken);
        } catch (InvalidTokenException e) {
            // The reason is deliberately not sent to the browser.
            log.warn("Rejected Firebase token: {}", e.getMessage());
            return unauthorized(Json.newObject().put("error", "We could not verify your sign-in. Please try again."));
        }

        // We link accounts by email, so the email must belong to the person signing in.
        if (fbUser.email == null || fbUser.email.isBlank() || !fbUser.emailVerified) {
            return unauthorized(Json.newObject().put("error",
                    "Your sign-in provider did not share a verified email address. "
                            + "Verify your email with the provider, or sign up with email and password."));
        }
        String email = fbUser.email.trim().toLowerCase(Locale.ROOT);

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            return startSession(request, existing.get(), false);
        }

        // ---- No account yet: only the Sign Up page may create one ----
        String role = json.path("role").asText(null);
        if (role == null || role.isBlank()) {
            return status(404, Json.newObject()
                    .put("error", "There is no ChamaSmart account for " + email + " yet. Please use Sign Up to create one.")
                    .put("needsSignUp", true));
        }
        if (!SELF_SERVICE_ROLES.contains(role)) {
            return badRequest(Json.newObject().put("error", "Please choose a valid role."));
        }
        if (!json.path("acceptTerms").asBoolean(false)) {
            return badRequest(Json.newObject().put("error", "Please accept the terms to create an account."));
        }
        String chamaName = json.path("chamaName").asText("").trim();
        if (chamaName.isEmpty() || chamaName.length() > 120) {
            return badRequest(Json.newObject().put("error", "Please enter your chama name."));
        }
        String phoneNo = json.path("phoneNo").asText("").trim();
        if (phoneNo.length() > 30) {
            return badRequest(Json.newObject().put("error", "That phone number is too long."));
        }
        String fullName = fbUser.name != null && !fbUser.name.isBlank() ? fbUser.name.trim() : email;

        User user = new User();
        user.fullname = fullName.length() > 120 ? fullName.substring(0, 120) : fullName;
        user.email = email;
        user.phoneno = phoneNo;
        // No real password exists for social accounts. This random marker can never be typed by a person,
        // and HomeController refuses to accept it at the email/password form.
        user.password = SOCIAL_PASSWORD_PREFIX + UUID.randomUUID();
        user.chamaName = chamaName;
        user.role = role;

        try {
            userRepository.save(user);
        } catch (RuntimeException e) {
            // Two sign-ins at the same moment can both try to create the account. Use the one that won.
            Optional<User> winner = userRepository.findByEmail(email);
            if (winner.isPresent()) {
                return startSession(request, winner.get(), false);
            }
            log.error("Could not create social user", e);
            return internalServerError(Json.newObject().put("error", "We could not create your account. Please try again."));
        }
        return startSession(request, user, true);
    }

    private Result startSession(Http.Request request, User user, boolean created) {
        String role = user.role == null ? "member" : user.role;
        String redirectUrl;
        switch (role) {
            case "platform_admin": redirectUrl = "/platform-admin"; break;
            case "chama_admin":    redirectUrl = "/chama-admin";    break;
            default:               redirectUrl = "/member";
        }
        return ok(Json.newObject()
                .put("message", created ? "Account created!" : "Login successful!")
                .put("created", created)
                .put("redirect", redirectUrl))
                .addingToSession(request, "userId", String.valueOf(user.id))
                .addingToSession(request, "userRole", role);
    }
}
