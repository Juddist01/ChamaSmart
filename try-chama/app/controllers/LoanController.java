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

    // POST /api/loans/approve
    public Result approveLoan(Http.Request request) {

        JsonNode json = request.body().asJson();

        if (json == null || !json.hasNonNull("loanId")) {
            return badRequest(Json.newObject().put("error", "loanId is required"));
        }

        String adminIdStr = request.session().get("userId").orElse(null);

        if (adminIdStr == null) {
            return unauthorized("Authentication required");
        }

        User admin;
        try {
            admin = User.find.byId(Long.parseLong(adminIdStr));
        } catch (NumberFormatException e) {
            return unauthorized("Invalid session");
        }

        if (admin == null || !"chama_admin".equals(admin.role)) {
            return unauthorized("Only Chama Admins can approve loans");
        }

        long loanId = json.path("loanId").asLong(-1);

        if (loanId <= 0) {
            return badRequest("Invalid loanId");
        }

        int updated = io.ebean.DB.sqlUpdate(
                "UPDATE loans SET approval_status = 'approved' "
                        + "WHERE id = :loanId AND approval_status = 'pending' "
                        + "AND chama_id = (SELECT chama_id FROM user WHERE id = :adminId)"
        )
                .setParameter("loanId", loanId)
                .setParameter("adminId", admin.id)
                .execute();

        if (updated == 0) {
            return badRequest(Json.newObject().put("error",
                    "Loan not found, not pending, or does not belong to your chama"));
        }

        return ok(Json.newObject().put("message", "Loan approved successfully"));
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
