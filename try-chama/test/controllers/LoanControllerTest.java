package controllers;

import org.junit.Test;
import play.db.Database;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.test.WithApplication;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.Assert.assertEquals;
import static play.test.Helpers.*;

public class LoanControllerTest extends WithApplication {

    private Database database() {
        return app.injector().instanceOf(Database.class);
    }

    private long createPendingLoan() throws Exception {
        String sql =
                "INSERT INTO loans " +
                "(user_id, chama_id, amount, interest_rate, duration_months, " +
                "total_to_pay, balance_remaining, approval_status, repayment_status) " +
                "VALUES (1, 2, 1000, 10, 6, 1100, 1100, 'pending', 'unpaid')";

        try (Connection connection = database().getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("Could not obtain temporary loan ID");
                }
                return keys.getLong(1);
            }
        }
    }

    private String getApprovalStatus(long loanId) throws Exception {
        try (Connection connection = database().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT approval_status FROM loans WHERE id = ?")) {

            statement.setLong(1, loanId);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Temporary loan not found: " + loanId);
                }
                return result.getString("approval_status");
            }
        }
    }

    private void deleteLoan(long loanId) throws Exception {
        try (Connection connection = database().getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM loan_repayments WHERE loan_id = ?")) {
                    statement.setLong(1, loanId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM transactions WHERE reference_id = ? " +
                        "AND type IN ('loan_disbursement', 'loan_repayment')")) {
                    statement.setLong(1, loanId);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM loans WHERE id = ?")) {
                    statement.setLong(1, loanId);
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }


    private int countDisbursements(long loanId) throws Exception {
        try (Connection connection = database().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM transactions " +
                     "WHERE type = 'loan_disbursement' AND reference_id = ?")) {
            statement.setLong(1, loanId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private double getDisbursementAmount(long loanId) throws Exception {
        try (Connection connection = database().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT amount FROM transactions " +
                     "WHERE type = 'loan_disbursement' AND reference_id = ?")) {
            statement.setLong(1, loanId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Disbursement not found");
                }
                return result.getDouble("amount");
            }
        }
    }

    private Result decideLoan(long loanId, String action) {
        Http.RequestBuilder request = new Http.RequestBuilder()
                .method(POST)
                .uri("/api/loans/approve")
                .session("userId", "3")
                .bodyJson(Json.newObject()
                        .put("loanId", loanId)
                        .put("action", action));

        return route(app, request);
    }


    private Result repayLoan(long loanId, String userId, double amount) {
        Http.RequestBuilder request = new Http.RequestBuilder()
                .method(POST)
                .uri("/api/loans/repay")
                .session("userId", userId)
                .bodyJson(Json.newObject()
                        .put("loanId", loanId)
                        .put("amount", amount)
                        .put("paymentReference", "TEST-PAY-" + loanId));
        return route(app, request);
    }

    private Result reviewRepayment(long repaymentId, String action) {
        Http.RequestBuilder request = new Http.RequestBuilder()
                .method(POST)
                .uri("/api/loans/repayments/review")
                .session("userId", "3")
                .bodyJson(Json.newObject()
                        .put("repaymentId", repaymentId)
                        .put("action", action));
        return route(app, request);
    }

    private long repaymentId(long loanId) throws Exception {
        try (Connection c = database().getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT id FROM loan_repayments WHERE loan_id = ?")) {
            s.setLong(1, loanId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Repayment claim not found");
                return r.getLong(1);
            }
        }
    }

    private String claimStatus(long loanId) throws Exception {
        try (Connection c = database().getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT status FROM loan_repayments WHERE loan_id = ?")) {
            s.setLong(1, loanId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Repayment claim not found");
                return r.getString(1);
            }
        }
    }

    private double loanBalance(long id) throws Exception {
        try (Connection c = database().getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT balance_remaining FROM loans WHERE id = ?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Loan not found");
                return r.getDouble(1);
            }
        }
    }

    private String repaymentStatus(long id) throws Exception {
        try (Connection c = database().getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT repayment_status FROM loans WHERE id = ?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Loan not found");
                return r.getString(1);
            }
        }
    }

    private int repaymentCount(long id) throws Exception {
        try (Connection c = database().getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT COUNT(*) FROM transactions WHERE type = 'loan_repayment' AND reference_id = ?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    @Test
    public void testLoanExceedingLimitIsRejected() {
        Http.RequestBuilder request = new Http.RequestBuilder()
                .method(POST)
                .uri("/api/loans/apply")
                .session("userId", "1")
                .bodyJson(Json.newObject()
                        .put("userId", 1)
                        .put("amount", 99999999.0)
                        .put("durationMonths", 6));

        Result result = route(app, request);
        assertEquals(BAD_REQUEST, result.status());
    }

    @Test
    public void testAdminCanApprovePendingLoan() throws Exception {
        long loanId = createPendingLoan();

        try {
            Result result = decideLoan(loanId, "approve");

            assertEquals(OK, result.status());
            assertEquals("approved", getApprovalStatus(loanId));
            assertEquals(1, countDisbursements(loanId));
            assertEquals(1000.0, getDisbursementAmount(loanId), 0.001);
        } finally {
            deleteLoan(loanId);
        }
    }

    @Test
    public void testAdminCanRejectPendingLoan() throws Exception {
        long loanId = createPendingLoan();

        try {
            Result result = decideLoan(loanId, "reject");

            assertEquals(OK, result.status());
            assertEquals("rejected", getApprovalStatus(loanId));
            assertEquals(0, countDisbursements(loanId));
        } finally {
            deleteLoan(loanId);
        }
    }

    @Test
    public void testAlreadyDecidedLoanCannotBeDecidedAgain() throws Exception {
        long loanId = createPendingLoan();

        try {
            Result firstDecision = decideLoan(loanId, "approve");
            assertEquals(OK, firstDecision.status());

            Result secondDecision = decideLoan(loanId, "reject");
            assertEquals(CONFLICT, secondDecision.status());

            assertEquals("approved", getApprovalStatus(loanId));
            assertEquals(1, countDisbursements(loanId));
        } finally {
            deleteLoan(loanId);
        }
    }

    @Test
    public void testRepaymentSubmissionDoesNotChangeBalance() throws Exception {
        long id = createPendingLoan();
        try {
            assertEquals(OK, decideLoan(id, "approve").status());
            assertEquals(OK, repayLoan(id, "1", 400).status());
            assertEquals(1100.0, loanBalance(id), 0.001);
            assertEquals("unpaid", repaymentStatus(id));
            assertEquals(0, repaymentCount(id));
            assertEquals("pending", claimStatus(id));
        } finally {
            deleteLoan(id);
        }
    }

    @Test
    public void testAdminConfirmationRecordsPartialRepayment() throws Exception {
        long id = createPendingLoan();
        try {
            assertEquals(OK, decideLoan(id, "approve").status());
            assertEquals(OK, repayLoan(id, "1", 400).status());
            assertEquals(OK, reviewRepayment(repaymentId(id), "confirm").status());
            assertEquals(700.0, loanBalance(id), 0.001);
            assertEquals("partial", repaymentStatus(id));
            assertEquals(1, repaymentCount(id));
            assertEquals("confirmed", claimStatus(id));
        } finally {
            deleteLoan(id);
        }
    }

    @Test
    public void testRepaymentClaimCannotBeReviewedTwice() throws Exception {
        long id = createPendingLoan();
        try {
            assertEquals(OK, decideLoan(id, "approve").status());
            assertEquals(OK, repayLoan(id, "1", 400).status());

            long claimId = repaymentId(id);
            assertEquals(OK, reviewRepayment(claimId, "confirm").status());
            assertEquals(409, reviewRepayment(claimId, "confirm").status());

            assertEquals(700.0, loanBalance(id), 0.001);
            assertEquals(1, repaymentCount(id));
            assertEquals("confirmed", claimStatus(id));
        } finally {
            deleteLoan(id);
        }
    }

    @Test
    public void testAdminRejectionLeavesBalanceUnchanged() throws Exception {
        long id = createPendingLoan();
        try {
            assertEquals(OK, decideLoan(id, "approve").status());
            assertEquals(OK, repayLoan(id, "1", 400).status());
            assertEquals(OK, reviewRepayment(repaymentId(id), "reject").status());
            assertEquals(1100.0, loanBalance(id), 0.001);
            assertEquals("unpaid", repaymentStatus(id));
            assertEquals(0, repaymentCount(id));
            assertEquals("rejected", claimStatus(id));
        } finally {
            deleteLoan(id);
        }
    }

    @Test
    public void testOverpaymentClaimIsRejected() throws Exception {
        long id = createPendingLoan();
        try {
            assertEquals(OK, decideLoan(id, "approve").status());
            assertEquals(BAD_REQUEST, repayLoan(id, "1", 1200).status());
            assertEquals(1100.0, loanBalance(id), 0.001);
            assertEquals("unpaid", repaymentStatus(id));
            assertEquals(0, repaymentCount(id));
        } finally {
            deleteLoan(id);
        }
    }

    @Test
    public void testAnotherUserCannotSubmitRepaymentClaim() throws Exception {
        long id = createPendingLoan();
        try {
            assertEquals(OK, decideLoan(id, "approve").status());
            assertEquals(FORBIDDEN, repayLoan(id, "3", 400).status());
            assertEquals(1100.0, loanBalance(id), 0.001);
            assertEquals(0, repaymentCount(id));
        } finally {
            deleteLoan(id);
        }
    }

}
