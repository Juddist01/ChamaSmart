package controllers;

import play.mvc.*;
import models.User;
import jakarta.inject.Inject;
import play.libs.Json;
import com.fasterxml.jackson.databind.JsonNode;
import repositories.UserRepository;
import java.util.Optional;
import org.mindrot.jbcrypt.BCrypt;
import play.libs.ws.WSClient;

public class HomeController extends Controller {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(HomeController.class);


    private final UserRepository userRepository;
    private final WSClient ws;

    @Inject
    public HomeController(UserRepository userRepository, WSClient ws) {
        this.userRepository = userRepository;
        this.ws = ws;
    }

    // ── Public pages ──────────────────────────────────────────────

    public Result index() {
        return ok(views.html.index.render());
    }

    @play.filters.csrf.AddCSRFToken
    public Result signIn(Http.Request request) {
        String csrfToken = play.filters.csrf.CSRF.getToken(request)
                .map(play.filters.csrf.CSRF.Token::value)
                .orElse("");

        return ok(views.html.auth.signIn.render(csrfToken));
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

        // Accounts created with Google/GitHub have no usable password: they must sign in that way.
        if (user.password == null || user.password.startsWith(AuthController.SOCIAL_PASSWORD_PREFIX)) {
            return unauthorized(Json.newObject().put("error",
                    "This account signs in with Google or GitHub. Use the buttons below the form."));
        }

        // --- BCRYPT CHECK LOCATION ---
        if (!BCrypt.checkpw(password, user.password)) {
            return unauthorized(Json.newObject().put("error", "Invalid email or password"));
        }

        // Decide redirect URL based on role
        String role = user.role == null ? "member" : user.role;
        String redirectUrl;
        switch (role) {
            case "platform_admin": redirectUrl = "/platform-admin"; break;
            case "chama_admin":    redirectUrl = "/chama-admin";    break;
            default:               redirectUrl = "/member";
        }

        return ok(Json.newObject()
                .put("message", "Login successful!")
                .put("redirect", redirectUrl))
                .addingToSession(request, "userId",   String.valueOf(user.id))
                .addingToSession(request, "userRole", role);
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
        chama_name = chama_name == null ? null : chama_name.trim();
        String role            = json.findPath("role").asText(null);
        boolean acceptTerms    = json.findPath("accept_terms").asBoolean(false);

        if (email == null || phoneno == null || password == null ||
                confirmPassword == null || role == null || !acceptTerms) {
            return badRequest(Json.newObject().put("error", "Please fill all fields and accept the terms."));
        }
        if (!AuthController.SELF_SERVICE_ROLES.contains(role)) {
            return badRequest(Json.newObject().put("error", "Please choose a valid role."));
        }
        if (!password.equals(confirmPassword)) {
            return badRequest(Json.newObject().put("error", "Passwords do not match."));
        }
        if (userRepository.findByEmail(email).isPresent()) {
            return badRequest(Json.newObject().put("error", "Email already registered."));
        }

        if (chama_name == null || chama_name.isEmpty()) {
            return badRequest(Json.newObject().put("error", "Please enter your Chama name."));
        }

        models.Chama existingChama = null;
        if ("member".equalsIgnoreCase(role)) {
            existingChama = models.Chama.find.query()
                    .where().eq("name", chama_name).findOne();
            if (existingChama == null) {
                return badRequest(Json.newObject().put("error",
                        "Chama not found. Please enter the exact name of an existing Chama."));
            }
        }

        User user     = new User();
        user.fullname = fullname;
        user.email    = email;
        user.phoneno  = phoneno;

        // --- BCRYPT HASH LOCATION ---
        user.password = BCrypt.hashpw(password, BCrypt.gensalt());

        user.chamaName = chama_name;
        user.role     = role;
        userRepository.save(user);

        // Step 4 Linking: Handle Chama creation/joining for form signups
        if ("chama_admin".equalsIgnoreCase(role)) {
            models.Chama chama = new models.Chama();
            chama.name = chama_name;
            chama.admin = user;
            chama.save();

            // Persist the generated Chama ID directly on the admin user.
            user.chamaId = chama.id;
            io.ebean.DB.sqlUpdate(
                    "UPDATE user SET chama_id = :chamaId WHERE id = :userId")
                .setParameter("chamaId", chama.id)
                .setParameter("userId", user.id)
                .execute();

            if (chama.members == null) chama.members = new java.util.ArrayList<>();
            chama.members.add(user);
            chama.save();
        } else {
            if (existingChama != null) {
                if (existingChama.members == null) existingChama.members = new java.util.ArrayList<>();
                existingChama.members.add(user);
                existingChama.save();

                // Keep the member's direct Chama reference in sync.
                user.chamaId = existingChama.id;
                io.ebean.DB.sqlUpdate(
                        "UPDATE user SET chama_id = :chamaId WHERE id = :userId")
                    .setParameter("chamaId", existingChama.id)
                    .setParameter("userId", user.id)
                    .execute();
            }
        }

        return ok(Json.newObject().put("message", "Account created! Please sign in."));
    }

    // ── Dashboards ────────────────────────────────────────────────

    // ── Member Dashboard ──
    @play.filters.csrf.AddCSRFToken
    public Result memberDashboard(Http.Request request) {
        User user = getUserFromSession(request);
        if (user == null) return redirect(routes.HomeController.signIn());

        double mySavings = 0.0;
        int myContributionCount = 0;
        double myActiveLoans = 0.0;
        double chamaSavings = 0.0;
        double monthlyTarget = 0.0;

        io.ebean.SqlRow rowSavings = io.ebean.DB.sqlQuery(
                "SELECT COALESCE(SUM(amount_paid), 0) AS total, COUNT(*) AS payment_count " +
                "FROM contributions WHERE user_id = :uId AND status = 'PAID'")
                .setParameter("uId", user.id)
                .findOne();

        if (rowSavings != null) {
            mySavings = rowSavings.getDouble("total");
            myContributionCount = rowSavings.getInteger("payment_count");
        }

        if (user.chamaId != null) {
            io.ebean.SqlRow rowChama = io.ebean.DB.sqlQuery(
                    "SELECT COALESCE(SUM(co.amount_paid), 0) AS total_savings, " +
                    "COALESCE(MAX(c.monthly_contribution_amount), 0) AS monthly_target " +
                    "FROM chama c LEFT JOIN contributions co " +
                    "ON co.chama_id = c.chama_id AND co.status = 'PAID' " +
                    "WHERE c.chama_id = :chamaId")
                    .setParameter("chamaId", user.chamaId)
                    .findOne();

            if (rowChama != null) {
                chamaSavings = rowChama.getDouble("total_savings");
                monthlyTarget = rowChama.getDouble("monthly_target");
            }
        }

        io.ebean.SqlRow rowLoans = io.ebean.DB.sqlQuery(
                "SELECT COALESCE(SUM(balance_remaining), 0) AS total " +
                "FROM loans WHERE user_id = :uId AND approval_status = 'approved'")
                .setParameter("uId", user.id)
                .findOne();

        if (rowLoans != null) {
            myActiveLoans = rowLoans.getDouble("total");
        }

        java.util.List<io.ebean.SqlRow> memberContributions =
                io.ebean.DB.sqlQuery(
                        "SELECT id, period_name, COALESCE(amount_paid, 0) AS amount, " +
                        "DATE_FORMAT(payment_date, '%d %b %Y %H:%i') AS paid_date, " +
                        "status, payment_method " +
                        "FROM contributions WHERE user_id = :uId " +
                        "ORDER BY payment_date DESC, id DESC LIMIT 50")
                .setParameter("uId", user.id)
                .findList();

        java.util.List<io.ebean.SqlRow> memberLoans =
                io.ebean.DB.sqlQuery(
                        "SELECT id, COALESCE(amount, 0) AS amount, " +
                        "COALESCE(total_to_pay, 0) AS total_to_pay, " +
                        "COALESCE(balance_remaining, 0) AS balance_remaining, " +
                        "approval_status, repayment_status, " +
                        "DATE_FORMAT(created_at, '%d %b %Y') AS applied_date " +
                        "FROM loans WHERE user_id = :uId " +
                        "ORDER BY created_at DESC, id DESC")
                .setParameter("uId", user.id)
                .findList();

        java.util.List<io.ebean.SqlRow> repaymentClaims =
                io.ebean.DB.sqlQuery(
                        "SELECT lr.id AS repayment_id, lr.loan_id, lr.amount, " +
                        "lr.payment_reference, lr.status, " +
                        "DATE_FORMAT(lr.submitted_at, '%d %b %Y %H:%i') AS submitted_date, " +
                        "DATE_FORMAT(lr.reviewed_at, '%d %b %Y %H:%i') AS reviewed_date, " +
                        "lr.review_note " +
                        "FROM loan_repayments lr WHERE lr.user_id = :uId " +
                        "ORDER BY lr.submitted_at DESC, lr.id DESC")
                .setParameter("uId", user.id)
                .findList();

        String csrfToken = play.filters.csrf.CSRF.getToken(request)
                .map(play.filters.csrf.CSRF.Token::value)
                .orElse("");

        return ok(views.html.dashboards.member.render(
                user, mySavings, myActiveLoans, chamaSavings,
                myContributionCount, monthlyTarget,
                memberContributions, memberLoans, csrfToken, repaymentClaims));
    }


    // ── Chama Admin Dashboard ──
    @play.filters.csrf.AddCSRFToken
    public Result chamaAdminDashboard(Http.Request request) {
        User user = getUserFromSession(request);

        if (user == null) {
            return redirect(routes.HomeController.signIn());
        }

        if (!"chama_admin".equals(user.role)) {
            return redirect(routes.HomeController.memberDashboard());
        }

        // Scope dashboard figures to chamas actually owned by this administrator.
        io.ebean.SqlRow rowSavings = io.ebean.DB.sqlQuery(
                "SELECT COALESCE(SUM(co.amount_paid), 0) AS total " +
                "FROM contributions co " +
                "JOIN chama c ON c.chama_id = co.chama_id " +
                "WHERE c.admin_id = :adminId AND co.status = 'PAID'")
                .setParameter("adminId", user.id)
                .findOne();

        double totalSavings = rowSavings != null
                ? rowSavings.getDouble("total") : 0.0;

        io.ebean.SqlRow rowMembers = io.ebean.DB.sqlQuery(
                "SELECT COUNT(DISTINCT cm.user_id) AS total " +
                "FROM chama_members cm " +
                "JOIN chama c ON c.chama_id = cm.chama_id " +
                "JOIN user u ON u.id = cm.user_id " +
                "WHERE c.admin_id = :adminId")
                .setParameter("adminId", user.id)
                .findOne();

        int totalMembers = rowMembers != null
                ? rowMembers.getInteger("total") : 0;

        io.ebean.SqlRow rowLoans = io.ebean.DB.sqlQuery(
                "SELECT COALESCE(SUM(l.amount), 0) AS total " +
                "FROM loans l " +
                "JOIN chama c ON c.chama_id = l.chama_id " +
                "WHERE c.admin_id = :adminId AND l.approval_status = 'approved'")
                .setParameter("adminId", user.id)
                .findOne();

        double activeLoansSum = rowLoans != null
                ? rowLoans.getDouble("total") : 0.0;

        // Fetch actual members of chamas owned by this administrator.
        java.util.List<io.ebean.SqlRow> members = io.ebean.DB.sqlQuery(
                "SELECT DISTINCT u.id AS member_id, u.fullname AS member_name, " +
                "u.phoneno AS phone, u.role AS member_role, " +
                "DATE_FORMAT(u.created_at, '%d %b %Y') AS joined_date, " +
                "(SELECT DATE_FORMAT(MAX(co.payment_date), '%d %b %Y') " +
                " FROM contributions co JOIN chama cc ON cc.chama_id = co.chama_id " +
                " WHERE co.user_id = u.id AND cc.admin_id = :adminId AND co.status = 'PAID') AS last_paid_date " +
                "FROM chama_members cm " +
                "JOIN chama c ON c.chama_id = cm.chama_id " +
                "JOIN user u ON u.id = cm.user_id " +
                "WHERE c.admin_id = :adminId " +
                "ORDER BY u.fullname")
                .setParameter("adminId", user.id)
                .findList();

        // Fetch the latest contribution records for those chamas.
        java.util.List<io.ebean.SqlRow> contributions = io.ebean.DB.sqlQuery(
                "SELECT co.id AS contribution_id, " +
                "COALESCE(u.fullname, co.user_name, 'Unknown member') AS member_name, " +
                "COALESCE(c.name, co.chama_name, 'Unknown chama') AS chama_name, " +
                "COALESCE(co.amount_paid, 0) AS amount, " +
                "COALESCE(co.expected_amount, 0) AS expected_amount, " +
                "co.period_name AS period_name, " +
                "DATE_FORMAT(co.due_date, '%d %b %Y') AS due_date_label, " +
                "co.status AS contribution_status, " +
                "DATE_FORMAT(co.payment_date, '%d %b %Y %H:%i') AS paid_date " +
                "FROM contributions co " +
                "JOIN chama c ON c.chama_id = co.chama_id " +
                "LEFT JOIN user u ON u.id = co.user_id " +
                "WHERE c.admin_id = :adminId " +
                "ORDER BY CASE WHEN co.status = 'EXPECTED' THEN 0 ELSE 1 END, " +
                "co.due_date DESC, co.payment_date DESC, co.id DESC LIMIT 50")
                .setParameter("adminId", user.id)
                .findList();

        // Fetch loan applications attached to chamas owned by this administrator.
        java.util.List<io.ebean.SqlRow> loans = io.ebean.DB.sqlQuery(
                "SELECT l.id AS loan_id, " +
                "COALESCE(NULLIF(l.user_name, ''), u.fullname, 'Unknown member') AS member_name, " +
                "COALESCE(l.amount, 0) AS amount, " +
                "l.approval_status AS approval_status, " +
                "COALESCE(l.credit_score, -1) AS credit_score, " +
                "CASE " +
                "WHEN LOWER(l.score_reason) LIKE '%risk_band=low%' OR LOWER(l.score_reason) LIKE '%risk band: low%' THEN 'Low' " +
                "WHEN LOWER(l.score_reason) LIKE '%risk_band=medium%' OR LOWER(l.score_reason) LIKE '%risk band: medium%' THEN 'Medium' " +
                "WHEN LOWER(l.score_reason) LIKE '%risk_band=high%' OR LOWER(l.score_reason) LIKE '%risk band: high%' THEN 'High' " +
                "ELSE 'Manual review' END AS risk_band, " +
                "DATE_FORMAT(l.created_at, '%d %b %Y') AS applied_date, " +
                "COALESCE(l.score_reason, 'No scoring details available') AS loan_reason " +
                "FROM loans l " +
                "JOIN chama c ON c.chama_id = l.chama_id " +
                "LEFT JOIN user u ON u.id = l.user_id " +
                "WHERE c.admin_id = :adminId " +
                "ORDER BY CASE WHEN l.approval_status = 'pending' THEN 0 ELSE 1 END, " +
                "l.created_at DESC")
                .setParameter("adminId", user.id)
                .findList();


        java.util.List<io.ebean.SqlRow> repaymentReviews =
                io.ebean.DB.sqlQuery(
                        "SELECT lr.id AS repayment_id, lr.loan_id, lr.amount, " +
                        "lr.payment_reference, lr.status, " +
                        "COALESCE(u.fullname, 'Unknown member') AS member_name, " +
                        "DATE_FORMAT(lr.submitted_at, '%d %b %Y %H:%i') AS submitted_date " +
                        "FROM loan_repayments lr " +
                        "JOIN chama c ON c.chama_id = lr.chama_id " +
                        "LEFT JOIN user u ON u.id = lr.user_id " +
                        "WHERE c.admin_id = :adminId " +
                        "ORDER BY CASE WHEN lr.status = 'pending' THEN 0 ELSE 1 END, " +
                        "lr.submitted_at DESC, lr.id DESC LIMIT 100")
                .setParameter("adminId", user.id)
                .findList();

        String csrfToken = play.filters.csrf.CSRF.getToken(request)
                .map(play.filters.csrf.CSRF.Token::value)
                .orElse("");

        return ok(views.html.dashboards.chamaAdmin.render(
                user,
                totalSavings,
                totalMembers,
                activeLoansSum,
                csrfToken,
                loans,
                members,
                contributions,
                repaymentReviews
        ));
    }


    // ── Generate Monthly Contribution Obligations ──────────────────────────────

    public Result generateMonthlyObligations(Http.Request request) {
        User admin = getUserFromSession(request);
        if (admin == null || !"chama_admin".equals(admin.role)) {
            return unauthorized(Json.newObject().put("error", "Unauthorized"));
        }

        JsonNode json = request.body().asJson();
        if (json == null) {
            return badRequest(Json.newObject().put("error", "JSON body required"));
        }

        String period = json.path("periodName").asText("").trim();
        String dueDateText = json.path("dueDate").asText("").trim();

        if (!period.matches("\\d{4}-\\d{2}")) {
            return badRequest(Json.newObject().put("error",
                    "periodName must use YYYY-MM format"));
        }

        java.time.LocalDate dueDate;
        try {
            dueDate = java.time.LocalDate.parse(dueDateText);
        } catch (java.time.DateTimeException e) {
            return badRequest(Json.newObject().put("error",
                    "dueDate must use YYYY-MM-DD format"));
        }

        if (!dueDateText.startsWith(period + "-")) {
            return badRequest(Json.newObject().put("error",
                    "Due date must fall within the selected contribution month"));
        }

        java.util.List<io.ebean.SqlRow> chamas = io.ebean.DB.sqlQuery(
                "SELECT chama_id, monthly_contribution_amount " +
                "FROM chama WHERE admin_id = :adminId")
                .setParameter("adminId", admin.id)
                .findList();

        if (chamas.isEmpty()) {
            return badRequest(Json.newObject().put("error",
                    "No Chama is linked to this admin account"));
        }

        int generated = 0;
        int skipped = 0;

        for (io.ebean.SqlRow chama : chamas) {
            Long chamaId = chama.getLong("chama_id");
            Double monthlyAmount = chama.getDouble("monthly_contribution_amount");

            if (monthlyAmount == null || monthlyAmount <= 0) {
                skipped++;
                continue;
            }

            io.ebean.DB.sqlUpdate(
                    "INSERT INTO contributions " +
                    "(user_id, chama_id, user_name, chama_name, amount_paid, " +
                    "expected_amount, due_date, payment_date, status, " +
                    "payment_method, period_name) " +
                    "SELECT cm.user_id, cm.chama_id, u.fullname, c.name, " +
                    "0, c.monthly_contribution_amount, :dueDate, NULL, " +
                    "'EXPECTED', NULL, :period " +
                    "FROM chama_members cm " +
                    "JOIN user u ON u.id = cm.user_id " +
                    "JOIN chama c ON c.chama_id = cm.chama_id " +
                    "WHERE cm.chama_id = :chamaId " +
                    "AND c.admin_id = :adminId " +
                    "ON DUPLICATE KEY UPDATE period_name = VALUES(period_name)")
                    .setParameter("dueDate", dueDate)
                    .setParameter("period", period)
                    .setParameter("chamaId", chamaId)
                    .setParameter("adminId", admin.id)
                    .execute();

            io.ebean.SqlRow count = io.ebean.DB.sqlQuery(
                    "SELECT COUNT(*) AS total FROM contributions " +
                    "WHERE chama_id = :chamaId AND period_name = :period")
                    .setParameter("chamaId", chamaId)
                    .setParameter("period", period)
                    .findOne();

            generated += count == null ? 0 : count.getInteger("total");
        }

        if (generated == 0) {
            return badRequest(Json.newObject().put("error",
                    "No member obligations were generated. Check Chama membership and monthly amounts."));
        }

        return ok(Json.newObject()
                .put("message", "Monthly obligations generated or already exist.")
                .put("period", period)
                .put("obligations", generated)
                .put("chamasSkippedForMissingAmount", skipped));
    }

    // ── Record Contribution ────────────────────────────────────────────────────

    public Result recordContribution(Http.Request request) {
        User admin = getUserFromSession(request);
        if (admin == null || !"chama_admin".equals(admin.role)) {
            return unauthorized(Json.newObject().put("error", "Unauthorized"));
        }

        JsonNode json = request.body().asJson();
        if (json == null || !json.path("userId").canConvertToLong()
                || !json.path("amount").isNumber()) {
            return badRequest(Json.newObject().put("error",
                    "A valid userId and numeric amount are required"));
        }

        long targetUserId = json.path("userId").asLong();
        double amount = json.path("amount").asDouble();

        if (targetUserId <= 0 || !Double.isFinite(amount) || amount <= 0) {
            return badRequest(Json.newObject().put("error",
                    "Member ID and contribution amount must be positive"));
        }

        String period = json.path("periodName").asText("").trim();
        if (!period.matches("\\d{4}-\\d{2}")) {
            return badRequest(Json.newObject().put("error",
                    "Contribution period must use YYYY-MM format"));
        }

        String paymentMethod = json.path("paymentMethod")
                .asText("M-PESA").trim().toUpperCase();
        if (!"M-PESA".equals(paymentMethod) && !"CASH".equals(paymentMethod)) {
            return badRequest(Json.newObject().put("error",
                    "Payment method must be M-PESA or CASH"));
        }

        boolean isTest = json.path("isTest").asBoolean(false);
        if (isTest) {
            return badRequest(Json.newObject().put("error",
                    "Test payments must not settle real monthly obligations. Use a separate test record."));
        }

        User targetUser = User.find.byId(targetUserId);
        if (targetUser == null) {
            return badRequest(Json.newObject().put("error", "Member not found"));
        }

        java.util.List<io.ebean.SqlRow> memberships = io.ebean.DB.sqlQuery(
                "SELECT c.chama_id FROM chama_members cm " +
                "JOIN chama c ON c.chama_id = cm.chama_id " +
                "WHERE cm.user_id = :targetId AND c.admin_id = :adminId")
                .setParameter("targetId", targetUserId)
                .setParameter("adminId", admin.id)
                .findList();

        if (memberships.isEmpty()) {
            return forbidden(Json.newObject().put("error",
                    "This member does not belong to a chama managed by you"));
        }

        if (memberships.size() > 1) {
            return status(409, Json.newObject().put("error",
                    "This member belongs to multiple chamas managed by you"));
        }

        Long chamaId = memberships.get(0).getLong("chama_id");

        int updated = io.ebean.DB.sqlUpdate(
                "UPDATE contributions SET amount_paid = :amount, " +
                "payment_date = NOW(), status = 'PAID', payment_method = :method " +
                "WHERE user_id = :userId AND chama_id = :chamaId " +
                "AND period_name = :period AND status = 'EXPECTED' " +
                "AND ROUND(expected_amount, 2) = ROUND(:amount, 2) " +
                "AND EXISTS (SELECT 1 FROM chama c " +
                "WHERE c.chama_id = contributions.chama_id " +
                "AND c.admin_id = :adminId)")
                .setParameter("amount", amount)
                .setParameter("method", paymentMethod)
                .setParameter("userId", targetUserId)
                .setParameter("chamaId", chamaId)
                .setParameter("period", period)
                .setParameter("adminId", admin.id)
                .execute();

        if (updated == 0) {
            return badRequest(Json.newObject().put("error",
                    "Payment not recorded. No unpaid obligation exists, or the amount does not exactly match the expected contribution. Partial payments are not supported."));
        }

        return ok(Json.newObject()
                .put("message", "Monthly contribution payment recorded successfully.")
                .put("status", "PAID")
                .put("period", period));
    }

    // ── Member Lateness Forecast Endpoint ──────────────────────────────────────

    public java.util.concurrent.CompletionStage<Result> getMemberForecast(
            Long id, Http.Request request) {

        User requester = getUserFromSession(request);
        if (requester == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    unauthorized(Json.newObject().put("error", "Authentication required")));
        }

        User target = User.find.byId(id);
        if (target == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    notFound(Json.newObject().put("error", "Member not found")));
        }

        boolean allowed = false;

        if ("member".equals(requester.role)
                && requester.id != null
                && requester.id.equals(target.id)) {
            allowed = true;
        } else if ("chama_admin".equals(requester.role)) {
            io.ebean.SqlRow membership = io.ebean.DB.sqlQuery(
                    "SELECT 1 AS allowed " +
                    "FROM chama_members cm " +
                    "JOIN chama c ON c.chama_id = cm.chama_id " +
                    "WHERE cm.user_id = :targetId AND c.admin_id = :adminId " +
                    "LIMIT 1")
                    .setParameter("targetId", target.id)
                    .setParameter("adminId", requester.id)
                    .findOne();

            allowed = membership != null;
        }

        if (!allowed) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    forbidden(Json.newObject().put("error",
                            "You are not allowed to view this member's forecast")));
        }

        java.util.List<io.ebean.SqlRow> rows = io.ebean.DB.sqlQuery(
                "SELECT CASE " +
                "WHEN payment_date IS NOT NULL " +
                "THEN GREATEST(DATEDIFF(payment_date, due_date), 0) " +
                "ELSE GREATEST(DATEDIFF(CURRENT_DATE(), due_date), 0) " +
                "END AS days_late " +
                "FROM contributions " +
                "WHERE user_id = :userId " +
                "AND status IN ('PAID', 'EXPECTED') " +
                "AND due_date IS NOT NULL " +
                "AND due_date <= CURRENT_DATE() " +
                "ORDER BY due_date ASC, id ASC")
                .setParameter("userId", target.id)
                .findList();

        if (rows.size() < 8) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    status(422, Json.newObject()
                            .put("userId", id)
                            .put("status", "insufficient_history")
                            .put("observations", rows.size())
                            .put("required", 8)
                            .put("message",
                                    "At least 8 due contribution observations are required.")));
        }

        com.fasterxml.jackson.databind.node.ArrayNode history = Json.newArray();
        for (io.ebean.SqlRow row : rows) {
            history.add(row.getDouble("days_late"));
        }

        com.fasterxml.jackson.databind.node.ObjectNode payload = Json.newObject();
        payload.put("member_id", String.valueOf(target.id));
        payload.set("history", history);
        payload.put("weeks", 4);

        String baseUrl = System.getenv().getOrDefault(
                "FORECASTING_URL", "http://localhost:5002");
        baseUrl = baseUrl.replaceAll("/+$", "");

        return ws.url(baseUrl + "/api/forecast")
                .setRequestTimeout(java.time.Duration.ofSeconds(10))
                .post(payload)
                .thenApply(response -> {
                    if (response.getStatus() == 200 && response.asJson() != null) {
                        return ok(response.asJson());
                    }

                    return status(502, Json.newObject()
                            .put("userId", id)
                            .put("error", "Forecasting service returned an unsuccessful response")
                            .put("serviceStatus", response.getStatus()));
                })
                .exceptionally(t -> {
                    log.error("Forecast service request failed for member {}", id, t);
                    return status(502, Json.newObject()
                            .put("userId", id)
                            .put("error", "Forecasting service is currently unavailable"));
                });
    }

    // ── Logout ────────────────────────────────────────────────────

    public Result logout(Http.Request request) {
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