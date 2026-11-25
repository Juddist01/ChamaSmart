package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import models.User;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.libs.ws.WSResponse;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public class LoanController extends Controller {

    private final WSClient ws;

    @Inject
    public LoanController(WSClient ws) {
        this.ws = ws;
    }

    // POST /api/loans/apply
    public CompletionStage<Result> applyForLoan(Http.Request request) {

        JsonNode json = request.body().asJson();

        if (json == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    badRequest(Json.newObject().put("error", "Invalid JSON payload"))
            );
        }

        if (!json.hasNonNull("amount")
                || !json.hasNonNull("durationMonths")) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    badRequest(Json.newObject().put("error",
                            "amount and durationMonths are required"))
            );
        }

        String sessionUserId = request.session().get("userId").orElse(null);
        if (sessionUserId == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    unauthorized(Json.newObject().put("error", "Authentication required"))
            );
        }

        long userId;
        try {
            userId = Long.parseLong(sessionUserId);
        } catch (NumberFormatException e) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    unauthorized(Json.newObject().put("error", "Invalid session"))
            );
        }

        double amount = json.path("amount").asDouble(-1);
        int durationMonths = json.path("durationMonths").asInt(-1);

        if (!Double.isFinite(amount) || amount <= 0
                || durationMonths < 1 || durationMonths > 60) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    badRequest(Json.newObject().put("error",
                            "Invalid amount or durationMonths"))
            );
        }

        User user = User.find.byId(userId);

        if (user == null || !"member".equals(user.role)) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    unauthorized(Json.newObject().put("error",
                            "Only authenticated members can apply for loans"))
            );
        }

        double totalContributions = calculateTotalContributions(userId);
        double maxAllowedLoan = totalContributions * 3.0;

        if (amount > maxAllowedLoan) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    badRequest(Json.newObject().put("error",
                            String.format(
                                    "Loan exceeds the 3x contribution limit. Maximum allowed: Ksh %.2f",
                                    maxAllowedLoan
                            )))
            );
        }

        // Calculate the six features expected by the trained model.
        double consistencyRate = calculateConsistencyRate(userId);
        double avgDaysLate = calculateAvgDaysLate(userId);
        int priorLoans = calculatePriorLoans(userId);
        double repaymentRate = calculateRepaymentRate(userId);
        long tenureWeeks = calculateTenureWeeks(userId);

        JsonNode mlPayload = Json.newObject()
                .put("consistency_rate", consistencyRate)
                .put("avg_days_late", avgDaysLate)
                .put("prior_loans", priorLoans)
                .put("repayment_rate", repaymentRate)
                .put("tenure_weeks", tenureWeeks)
                .put("total_contributions", totalContributions);

        String scoringBaseUrl = System.getenv().getOrDefault(
                "CREDIT_SCORING_URL", "http://localhost:5001"
        );

        String scoringUrl = scoringBaseUrl.replaceAll("/+$", "") + "/api/score";

        return ws.url(scoringUrl)
                .setRequestTimeout(Duration.ofSeconds(5))
                .post(mlPayload)
                .handle((response, error) -> {

                    String applicationStatus = "needs_review";
                    int storedScore = 0;
                    String riskBand = "unavailable";
                    String reason;

                    if (error != null) {
                        reason = "Credit scoring service unavailable. Manual review required.";
                    } else if (response.getStatus() != 200) {
                        reason = "Credit scoring service returned HTTP "
                                + response.getStatus() + ". Manual review required.";
                    } else {
                        JsonNode result = response.asJson();

                        if (result != null
                                && "scored".equals(result.path("status").asText())
                                && result.hasNonNull("risk_score")) {

                            double riskScore = result.path("risk_score").asDouble();
                            riskBand = result.path("risk_band").asText("unknown");

                            // Legacy database column is an integer named credit_score.
                            // This stores the model score multiplied by 100.
                            storedScore = (int) Math.round(riskScore * 100);
                            applicationStatus = "scored";

                            reason = "Model risk score: " + riskScore
                                    + "; risk band: " + riskBand
                                    + ". " + result.path("note").asText("");

                            JsonNode factors = result.path("top_factors");
                            if (factors.isArray()) {
                                reason += " Top factors: " + factors.toString();
                            }

                        } else {
                            reason = result != null
                                    ? result.path("reason").asText(
                                            "Model could not score this application. Manual review required.")
                                    : "Invalid response from scoring service. Manual review required.";
                        }
                    }

                    // Every application starts pending, regardless of model result.
                    saveLoanRecord(
                            user, amount, durationMonths,
                            storedScore, reason, applicationStatus
                    );

                    return ok(Json.newObject()
                            .put("message", "Loan application submitted successfully")
                            .put("status", "pending")
                            .put("scoringStatus", applicationStatus)
                            .put("riskBand", riskBand)
                            .put("riskScore", storedScore / 100.0)
                            .put("reason", reason));
                });
    }

    // POST /api/loans/repay
    // Member submits a repayment claim; the balance is unchanged until admin confirmation.
    public Result repayLoan(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null || !json.path("loanId").canConvertToLong()
                || !json.path("amount").isNumber()) {
            return badRequest(Json.newObject().put("error",
                    "A valid loanId and numeric amount are required"));
        }

        long loanId = json.path("loanId").asLong(-1);
        java.math.BigDecimal amount = json.path("amount").decimalValue();
        String reference = json.path("paymentReference").asText("").trim();

        if (loanId <= 0 || amount.compareTo(java.math.BigDecimal.ZERO) <= 0
                || amount.stripTrailingZeros().scale() > 2
                || reference.isEmpty() || reference.length() > 120) {
            return badRequest(Json.newObject().put("error",
                    "Provide a valid loan, positive amount (maximum 2 decimals), and payment reference"));
        }

        String sessionId = request.session().get("userId").orElse(null);
        if (sessionId == null) {
            return unauthorized(Json.newObject().put("error", "Authentication required"));
        }

        long userId;
        try {
            userId = Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            return unauthorized(Json.newObject().put("error", "Invalid session"));
        }

        try (io.ebean.Transaction txn = io.ebean.DB.beginTransaction()) {
            io.ebean.SqlRow loan = io.ebean.DB.sqlQuery(
                    "SELECT id, user_id, chama_id, approval_status, balance_remaining " +
                    "FROM loans WHERE id = :loanId FOR UPDATE")
                    .setParameter("loanId", loanId).findOne();

            if (loan == null) return notFound(Json.newObject().put("error", "Loan not found"));
            if (loan.getLong("user_id") != userId) {
                return forbidden(Json.newObject().put("error", "You can only repay your own loans"));
            }
            if (!"approved".equalsIgnoreCase(loan.getString("approval_status"))) {
                return status(409, Json.newObject().put("error", "Only approved loans can be repaid"));
            }

            java.math.BigDecimal balance = java.math.BigDecimal.valueOf(
                    loan.getDouble("balance_remaining"));
            io.ebean.SqlRow pendingRow = io.ebean.DB.sqlQuery(
                    "SELECT COALESCE(SUM(amount), 0) AS total FROM loan_repayments " +
                    "WHERE loan_id = :loanId AND status = 'pending'")
                    .setParameter("loanId", loanId).findOne();
            java.math.BigDecimal pending = pendingRow == null
                    ? java.math.BigDecimal.ZERO
                    : pendingRow.get("total") == null ? java.math.BigDecimal.ZERO
                    : new java.math.BigDecimal(pendingRow.get("total").toString());
            java.math.BigDecimal available = balance.subtract(pending);

            if (amount.compareTo(available) > 0) {
                return badRequest(Json.newObject().put("error",
                        "Repayment exceeds the available balance after pending claims")
                        .put("outstandingBalance", balance)
                        .put("pendingClaims", pending)
                        .put("availableForClaim", available.max(java.math.BigDecimal.ZERO)));
            }

            int inserted = io.ebean.DB.sqlUpdate(
                    "INSERT INTO loan_repayments " +
                    "(loan_id, user_id, chama_id, amount, payment_reference, status) " +
                    "VALUES (:loanId, :userId, :chamaId, :amount, :reference, 'pending')")
                    .setParameter("loanId", loanId)
                    .setParameter("userId", userId)
                    .setParameter("chamaId", loan.getLong("chama_id"))
                    .setParameter("amount", amount)
                    .setParameter("reference", reference)
                    .execute();

            if (inserted != 1) throw new IllegalStateException("Claim was not saved");
            txn.commit();

            return ok(Json.newObject()
                    .put("message", "Repayment submitted for administrator verification")
                    .put("status", "pending")
                    .put("loanId", loanId)
                    .put("amount", amount)
                    .put("paymentReference", reference)
                    .put("balanceUnchanged", true));
        } catch (Exception e) {
            return internalServerError(Json.newObject().put("error",
                    "Repayment claim could not be saved. No changes were committed."));
        }
    }

    // POST /api/loans/repayments/review
    // Chama admin confirms or rejects a member's pending repayment claim.
    public Result reviewRepayment(Http.Request request) {
        JsonNode json = request.body().asJson();
        if (json == null || !json.path("repaymentId").canConvertToLong()) {
            return badRequest(Json.newObject().put("error", "A valid repaymentId is required"));
        }

        long repaymentId = json.path("repaymentId").asLong(-1);
        String action = json.path("action").asText("").trim().toLowerCase();
        String note = json.path("note").asText("").trim();

        if (repaymentId <= 0 || (!"confirm".equals(action) && !"reject".equals(action))) {
            return badRequest(Json.newObject().put("error",
                    "Provide a valid repaymentId and action: confirm or reject"));
        }
        if (note.length() > 500) {
            return badRequest(Json.newObject().put("error", "Review note is too long"));
        }

        String sessionId = request.session().get("userId").orElse(null);
        if (sessionId == null) {
            return unauthorized(Json.newObject().put("error", "Authentication required"));
        }

        User admin;
        try {
            admin = User.find.byId(Long.parseLong(sessionId));
        } catch (NumberFormatException e) {
            return unauthorized(Json.newObject().put("error", "Invalid session"));
        }
        if (admin == null || !"chama_admin".equals(admin.role)) {
            return forbidden(Json.newObject().put("error", "Only Chama Admins can review repayments"));
        }

        try (io.ebean.Transaction txn = io.ebean.DB.beginTransaction()) {
            io.ebean.SqlRow claim = io.ebean.DB.sqlQuery(
                    "SELECT r.id, r.loan_id, r.user_id, r.chama_id, r.amount, r.status, " +
                    "l.balance_remaining FROM loan_repayments r " +
                    "JOIN loans l ON l.id = r.loan_id " +
                    "WHERE r.id = :repaymentId FOR UPDATE")
                    .setParameter("repaymentId", repaymentId).findOne();

            if (claim == null) return notFound(Json.newObject().put("error", "Repayment claim not found"));
            if (!"pending".equalsIgnoreCase(claim.getString("status"))) {
                return status(409, Json.newObject().put("error", "This repayment has already been reviewed"));
            }

            io.ebean.SqlRow ownership = io.ebean.DB.sqlQuery(
                    "SELECT chama_id FROM chama WHERE chama_id = :chamaId AND admin_id = :adminId")
                    .setParameter("chamaId", claim.getLong("chama_id"))
                    .setParameter("adminId", admin.id).findOne();
            if (ownership == null) {
                return forbidden(Json.newObject().put("error",
                        "This repayment does not belong to a chama you manage"));
            }

            java.math.BigDecimal amount = new java.math.BigDecimal(claim.get("amount").toString());
            if ("confirm".equals(action)) {
                java.math.BigDecimal balance = new java.math.BigDecimal(
                        claim.get("balance_remaining").toString());
                if (amount.compareTo(balance) > 0) {
                    return status(409, Json.newObject().put("error",
                            "The repayment exceeds the current outstanding loan balance"));
                }

                int updatedLoan = io.ebean.DB.sqlUpdate(
                        "UPDATE loans SET " +
                        "repayment_status = CASE WHEN balance_remaining - :amount <= 0.005 " +
                        "THEN 'paid' ELSE 'partial' END, " +
                        "balance_remaining = GREATEST(balance_remaining - :amount, 0) " +
                        "WHERE id = :loanId AND approval_status = 'approved' " +
                        "AND balance_remaining >= :amount")
                        .setParameter("amount", amount)
                        .setParameter("loanId", claim.getLong("loan_id")).execute();
                if (updatedLoan != 1) {
                    return status(409, Json.newObject().put("error", "Loan balance changed; review again"));
                }

                int recorded = io.ebean.DB.sqlUpdate(
                        "INSERT INTO transactions " +
                        "(user_id, chama_id, type, amount, reference_id, description) " +
                        "VALUES (:userId, :chamaId, 'loan_repayment', :amount, :loanId, :description)")
                        .setParameter("userId", claim.getLong("user_id"))
                        .setParameter("chamaId", claim.getLong("chama_id"))
                        .setParameter("amount", amount)
                        .setParameter("loanId", claim.getLong("loan_id"))
                        .setParameter("description", "Verified repayment claim #" + repaymentId)
                        .execute();
                if (recorded != 1) throw new IllegalStateException("Repayment transaction was not recorded");
            }

            int reviewed = io.ebean.DB.sqlUpdate(
                    "UPDATE loan_repayments SET status = :newStatus, reviewed_by = :adminId, " +
                    "reviewed_at = CURRENT_TIMESTAMP, review_note = :note " +
                    "WHERE id = :repaymentId AND status = 'pending'")
                    .setParameter("newStatus", "confirm".equals(action) ? "confirmed" : "rejected")
                    .setParameter("adminId", admin.id)
                    .setParameter("note", note.isEmpty() ? null : note)
                    .setParameter("repaymentId", repaymentId).execute();
            if (reviewed != 1) throw new IllegalStateException("Claim review was not saved");

            txn.commit();
            return ok(Json.newObject()
                    .put("message", "Repayment " + ("confirm".equals(action) ? "confirmed" : "rejected"))
                    .put("repaymentId", repaymentId)
                    .put("status", "confirm".equals(action) ? "confirmed" : "rejected"));
        } catch (Exception e) {
            return internalServerError(Json.newObject().put("error",
                    "Repayment review failed. No changes were committed."));
        }
    }

    // POST /api/loans/approve
    // Supports both approval and rejection of pending applications.
    public Result approveLoan(Http.Request request) {
        JsonNode json = request.body().asJson();

        if (json == null || !json.path("loanId").canConvertToLong()) {
            return badRequest(Json.newObject().put("error", "A valid loanId is required"));
        }

        long loanId = json.path("loanId").asLong(-1);
        String action = json.path("action").asText("approve").trim().toLowerCase();

        if (loanId <= 0) {
            return badRequest(Json.newObject().put("error", "Invalid loanId"));
        }

        if (!"approve".equals(action) && !"reject".equals(action)) {
            return badRequest(Json.newObject().put("error",
                    "Action must be approve or reject"));
        }

        String adminIdStr = request.session().get("userId").orElse(null);
        if (adminIdStr == null) {
            return unauthorized(Json.newObject().put("error", "Authentication required"));
        }

        User admin;
        try {
            admin = User.find.byId(Long.parseLong(adminIdStr));
        } catch (NumberFormatException e) {
            return unauthorized(Json.newObject().put("error", "Invalid session"));
        }

        if (admin == null || !"chama_admin".equals(admin.role)) {
            return forbidden(Json.newObject().put("error",
                    "Only Chama Admins can decide loan applications"));
        }

        String newStatus = "approve".equals(action) ? "approved" : "rejected";

        try (io.ebean.Transaction txn = io.ebean.DB.beginTransaction()) {
            int updated = io.ebean.DB.sqlUpdate(
                    "UPDATE loans SET approval_status = :newStatus " +
                    "WHERE id = :loanId AND approval_status = 'pending' " +
                    "AND chama_id IN (SELECT chama_id FROM chama WHERE admin_id = :adminId)")
                    .setParameter("newStatus", newStatus)
                    .setParameter("loanId", loanId)
                    .setParameter("adminId", admin.id)
                    .execute();

            if (updated == 0) {
                return status(409, Json.newObject().put("error",
                        "Loan not found, already decided, or not managed by your chama"));
            }

            if ("approved".equals(newStatus)) {
                int recorded = io.ebean.DB.sqlUpdate(
                        "INSERT INTO transactions " +
                        "(user_id, chama_id, type, amount, reference_id, description) " +
                        "SELECT user_id, chama_id, 'loan_disbursement', amount, id, " +
                        "CONCAT('Loan disbursement for loan #', id) " +
                        "FROM loans WHERE id = :loanId " +
                        "AND approval_status = 'approved' " +
                        "AND NOT EXISTS (" +
                        "SELECT 1 FROM transactions " +
                        "WHERE type = 'loan_disbursement' AND reference_id = :loanId)")
                        .setParameter("loanId", loanId)
                        .execute();

                if (recorded != 1) {
                    throw new IllegalStateException(
                            "Loan approval succeeded but disbursement was not recorded");
                }
            }

            txn.commit();

            return ok(Json.newObject()
                    .put("message", "Loan " + newStatus + " successfully")
                    .put("loanId", loanId)
                    .put("approvalStatus", newStatus));
        } catch (Exception e) {
            return internalServerError(Json.newObject()
                    .put("error", "Loan decision could not be completed. No changes were committed."));
        }
    }

    private double calculateTotalContributions(long userId) {
        io.ebean.SqlRow row = io.ebean.DB.sqlQuery(
                "SELECT COALESCE(SUM(amount_paid), 0) AS total "
                        + "FROM contributions WHERE user_id = :userId AND status = 'PAID'"
        ).setParameter("userId", userId).findOne();

        return row == null || row.get("total") == null
                ? 0.0 : row.getDouble("total");
    }

    private double calculateConsistencyRate(long userId) {
        io.ebean.SqlRow row = io.ebean.DB.sqlQuery(
                "SELECT COUNT(*) AS total_paid, "
                        + "SUM(CASE WHEN DATE(payment_date) <= due_date THEN 1 ELSE 0 END) AS on_time "
                        + "FROM contributions "
                        + "WHERE user_id = :userId AND status = 'PAID' AND payment_date IS NOT NULL"
        ).setParameter("userId", userId).findOne();

        if (row == null || row.get("total_paid") == null
                || row.getLong("total_paid") == 0) {
            return 0.0;
        }

        return row.getDouble("on_time") / row.getDouble("total_paid");
    }

    private double calculateAvgDaysLate(long userId) {
        io.ebean.SqlRow row = io.ebean.DB.sqlQuery(
                "SELECT COALESCE(AVG(GREATEST(DATEDIFF(payment_date, due_date), 0)), 0) "
                        + "AS avg_late FROM contributions "
                        + "WHERE user_id = :userId AND status = 'PAID' AND payment_date IS NOT NULL"
        ).setParameter("userId", userId).findOne();

        return row == null || row.get("avg_late") == null
                ? 0.0 : Math.max(0.0, row.getDouble("avg_late"));
    }

    private int calculatePriorLoans(long userId) {
        io.ebean.SqlRow row = io.ebean.DB.sqlQuery(
                "SELECT COUNT(*) AS total FROM loans WHERE user_id = :userId"
        ).setParameter("userId", userId).findOne();

        return row == null ? 0 : row.getInteger("total");
    }

    private double calculateRepaymentRate(long userId) {
        io.ebean.SqlRow row = io.ebean.DB.sqlQuery(
                "SELECT COUNT(*) AS total, "
                        + "SUM(CASE WHEN balance_remaining <= 0 THEN 1 ELSE 0 END) AS repaid "
                        + "FROM loans WHERE user_id = :userId"
        ).setParameter("userId", userId).findOne();

        if (row == null || row.get("total") == null
                || row.getLong("total") == 0) {
            // Matches the training dataset convention for users with no loan history.
            return 0.5;
        }

        return row.getDouble("repaid") / row.getDouble("total");
    }

    private long calculateTenureWeeks(long userId) {
        io.ebean.SqlRow row = io.ebean.DB.sqlQuery(
                "SELECT GREATEST(TIMESTAMPDIFF(WEEK, created_at, NOW()), 0) "
                        + "AS tenure FROM user WHERE id = :userId"
        ).setParameter("userId", userId).findOne();

        return row == null || row.get("tenure") == null
                ? 0 : row.getLong("tenure");
    }

    private void saveLoanRecord(
            User user, double amount, int duration,
            int score, String reason, String scoringStatus
    ) {
        double interestRate = 10.0;
        double totalToPay = amount * (1 + interestRate / 100.0);

        io.ebean.DB.sqlUpdate(
                "INSERT INTO loans "
                        + "(user_id, user_name, amount, interest_rate, duration_months, "
                        + "total_to_pay, balance_remaining, approval_status, "
                        + "credit_score, score_reason, status, repayment_status, chama_id) "
                        + "VALUES (:userId, :userName, :amount, :rate, :duration, "
                        + ":total, :balance, 'pending', :score, :reason, "
                        + ":scoringStatus, 'unpaid', "
                        + "(SELECT chama_id FROM user WHERE id = :userId))"
        )
                .setParameter("userId", user.id)
                .setParameter("userName", user.fullname)
                .setParameter("amount", amount)
                .setParameter("rate", interestRate)
                .setParameter("duration", duration)
                .setParameter("total", totalToPay)
                .setParameter("balance", totalToPay)
                .setParameter("score", score)
                .setParameter("reason", reason)
                .setParameter("scoringStatus", scoringStatus)
                .execute();
    }
}
