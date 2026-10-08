package controllers;

import play.mvc.*;
import models.User;
import jakarta.inject.Inject;
import play.libs.Json;
import com.fasterxml.jackson.databind.JsonNode;
import repositories.UserRepository;
import java.util.Optional;

public class HomeController extends Controller {

    private final UserRepository userRepository;

    @Inject
    public HomeController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // ── Public pages ──────────────────────────────────────────────

    public Result index() {
        return ok(views.html.index.render());
    }

    public Result signIn(Http.Request request) {
        return ok(views.html.auth.signIn.render());
    }

    public Result signUp(Http.Request request) {
        return ok(views.html.auth.signUp.render());
    }

    public Result forgotPassword(Http.Request request) {
        return ok(views.html.auth.forgotPassword.render());
    }

    // ── Auth ──────────────────────────────────────────────────────

    public Result signInSubmit(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }

        String email    = json.findPath("email").asText(null);
        String password = json.findPath("password").asText(null);

        if (email == null || email.isEmpty() || password == null || password.isEmpty()) {
            return badRequest(Json.newObject().put("error", "Email and password are required"));
        }

        Optional<User> userOpt = userRepository.findByEmail(email);

        if (userOpt.isEmpty()) {
            return unauthorized(Json.newObject().put("error", "Invalid email or password"));
        }

        User user = userOpt.get();

        // TODO: replace plain-text compare with BCrypt in production
        if (!password.equals(user.password)) {
            return unauthorized(Json.newObject().put("error", "Invalid email or password"));
        }

        // Decide redirect URL based on role
        String redirectUrl;
        switch (user.role) {
            case "platform_admin": redirectUrl = "/platform-admin"; break;
            case "chama_admin":    redirectUrl = "/chama-admin";    break;
            default:               redirectUrl = "/member";
        }

        // Return JSON — the JS in main.js reads this and does window.location
        return ok(Json.newObject()
                .put("message", "Login successful!")
                .put("redirect", redirectUrl))
                // Store userId in session cookie for dashboard auth checks
                .addingToSession(request, "userId",   String.valueOf(user.id))
                .addingToSession(request, "userRole", user.role);
    }

    public Result signUpSubmit(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "Expecting JSON data"));
        }

        String fullname        = json.findPath("fullName").asText(null);
        String email           = json.findPath("email").asText(null);
        String phoneno         = json.findPath("phoneNo").asText(null);
        String password        = json.findPath("password").asText(null);
        String confirmPassword = json.findPath("confirm_password").asText(null);
        String chama_name      = json.findPath("chama_name").asText(null);
        String role            = json.findPath("role").asText(null);
        boolean acceptTerms    = json.findPath("accept_terms").asBoolean(false);

        if (email == null || phoneno == null || password == null ||
                confirmPassword == null || role == null || !acceptTerms) {
            return badRequest(Json.newObject().put("error", "Please fill all fields and accept the terms."));
        }
        if (!password.equals(confirmPassword)) {
            return badRequest(Json.newObject().put("error", "Passwords do not match."));
        }
        if (userRepository.findByEmail(email).isPresent()) {
            return badRequest(Json.newObject().put("error", "Email already registered."));
        }

        User user     = new User();
        user.fullname = fullname;
        user.email    = email;
        user.phoneno  = phoneno;
        user.password = password;
        user.chamaName = chama_name;
        user.role     = role;
        userRepository.save(user);

        return ok(Json.newObject().put("message", "Account created! Please sign in."));
    }

    // ── Dashboards ────────────────────────────────────────────────
    // Each dashboard reads the session to find the user,
    // then passes the User object to the template.

    public Result memberDashboard(Http.Request request) {
        User user = getUserFromSession(request);
        if (user == null) return redirect(routes.HomeController.signIn());
        return ok(views.html.dashboards.member.render(user));
    }

    public Result chamaAdminDashboard(Http.Request request) {
        User user = getUserFromSession(request);
        if (user == null) return redirect(routes.HomeController.signIn());
        // Guard: non-admins can't access admin dashboard
        if (!"chama_admin".equals(user.role)) {
            return redirect(routes.HomeController.memberDashboard());
        }
        return ok(views.html.dashboards.chamaAdmin.render(user));
    }

    public Result platformAdminDashboard(Http.Request request) {
        User user = getUserFromSession(request);
        if (user == null) return redirect(routes.HomeController.signIn());
        if (!"platform_admin".equals(user.role)) {
            return redirect(routes.HomeController.memberDashboard());
        }
        return ok(views.html.dashboards.platformAdmin.render(user));
    }

    // ── Logout ────────────────────────────────────────────────────

    public Result logout(Http.Request request) {
        // withNewSession() wipes the session cookie → user is signed out
        return redirect(routes.HomeController.signIn()).withNewSession();
    }

    // ── Helper ────────────────────────────────────────────────────

    private User getUserFromSession(Http.Request request) {
        Optional<String> userIdOpt = request.session().get("userId");
        if (userIdOpt.isEmpty()) return null;
        try {
            return User.find.byId(Long.parseLong(userIdOpt.get()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}