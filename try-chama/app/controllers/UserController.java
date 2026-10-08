package controllers;

import models.User;
import models.Transaction;
import play.mvc.*;
import play.libs.Json;
import com.fasterxml.jackson.databind.JsonNode;
import repositories.UserRepository;
import repositories.TransactionRepository;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

public class UserController extends Controller {

    private final UserRepository        userRepository;
    private final TransactionRepository transactionRepository;

    @Inject
    public UserController(UserRepository userRepository,
                          TransactionRepository transactionRepository) {
        this.userRepository        = userRepository;
        this.transactionRepository = transactionRepository;
    }

    // GET /profile
    public Result getProfile(Http.Request request) {
        User user = getUserFromSession(request);
        if (user == null) return unauthorized(Json.newObject().put("error", "Not logged in"));
        return ok(Json.newObject()
                .put("id",        user.id)
                .put("fullname",  user.fullname)
                .put("email",     user.email)
                .put("phoneno",   user.phoneno)
                .put("role",      user.role)
                .put("chamaName", user.chamaName != null ? user.chamaName : ""));
    }

    // POST /profile/update
    public Result updateProfile(Http.Request request) {
        User user = getUserFromSession(request);
        if (user == null) return unauthorized(Json.newObject().put("error", "Not logged in"));
        JsonNode json = request.body().asJson();
        if (json == null) return badRequest(Json.newObject().put("error", "Expecting JSON"));
        String fullname = json.findPath("fullname").asText(null);
        String phoneno  = json.findPath("phoneno").asText(null);
        if (fullname != null && !fullname.isBlank()) user.fullname = fullname;
        if (phoneno  != null && !phoneno.isBlank())  user.phoneno  = phoneno;
        user.update();
        return ok(Json.newObject().put("message", "Profile updated successfully"));
    }

    // POST /profile/change-password
    public Result changePassword(Http.Request request) {
        User user = getUserFromSession(request);
        if (user == null) return unauthorized(Json.newObject().put("error", "Not logged in"));
        JsonNode json = request.body().asJson();
        if (json == null) return badRequest(Json.newObject().put("error", "Expecting JSON"));
        String currentPassword = json.findPath("currentPassword").asText(null);
        String newPassword     = json.findPath("newPassword").asText(null);
        String confirmPassword = json.findPath("confirmPassword").asText(null);
        if (currentPassword == null || newPassword == null || confirmPassword == null)
            return badRequest(Json.newObject().put("error", "All password fields are required"));
        if (!currentPassword.equals(user.password))
            return badRequest(Json.newObject().put("error", "Current password is incorrect"));
        if (!newPassword.equals(confirmPassword))
            return badRequest(Json.newObject().put("error", "New passwords do not match"));
        if (newPassword.length() < 8)
            return badRequest(Json.newObject().put("error", "Password must be at least 8 characters"));
        user.password = newPassword;
        user.update();
        return ok(Json.newObject().put("message", "Password changed successfully"));
    }

    // GET /transactions  — member's own history
    public Result myTransactions(Http.Request request) {
        User user = getUserFromSession(request);
        if (user == null) return unauthorized(Json.newObject().put("error", "Not logged in"));
        List<Transaction> txns = transactionRepository.findByUser(user.id);
        var arr = Json.newArray();
        for (Transaction t : txns) {
            arr.add(Json.newObject()
                    .put("id",          t.id)
                    .put("type",        t.type)
                    .put("amount",      t.amount)
                    .put("description", t.description != null ? t.description : "")
                    .put("createdAt",   t.createdAt  != null ? t.createdAt.toString() : ""));
        }
        return ok(arr);
    }

    // GET /admin/members  — chama admin only
    public Result listMembers(Http.Request request) {
        User user = getUserFromSession(request);
        if (user == null) return unauthorized(Json.newObject().put("error", "Not logged in"));
        if (!"chama_admin".equals(user.role) && !"platform_admin".equals(user.role))
            return forbidden(Json.newObject().put("error", "Access denied"));
        List<User> members = User.find.all();
        var arr = Json.newArray();
        for (User m : members) {
            arr.add(Json.newObject()
                    .put("id",       m.id)
                    .put("fullname", m.fullname != null ? m.fullname : m.email)
                    .put("email",    m.email)
                    .put("phoneno",  m.phoneno  != null ? m.phoneno  : "")
                    .put("role",     m.role));
        }
        return ok(arr);
    }

    // POST /admin/members/suspend/:id
    public Result suspendMember(Http.Request request, Long memberId) {
        User admin = getUserFromSession(request);
        if (admin == null) return unauthorized(Json.newObject().put("error", "Not logged in"));
        if (!"chama_admin".equals(admin.role) && !"platform_admin".equals(admin.role))
            return forbidden(Json.newObject().put("error", "Access denied"));
        User member = User.find.byId(memberId);
        if (member == null) return notFound(Json.newObject().put("error", "Member not found"));
        // TODO: member.status = "suspended"; member.update();
        return ok(Json.newObject().put("message", "Member suspended"));
    }

    private User getUserFromSession(Http.Request request) {
        Optional<String> uid = request.session().get("userId");
        if (uid.isEmpty()) return null;
        try { return User.find.byId(Long.parseLong(uid.get())); }
        catch (NumberFormatException e) { return null; }
    }
}