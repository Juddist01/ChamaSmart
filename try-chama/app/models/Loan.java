package models;

import io.ebean.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A Loan is money borrowed from the chama pool by a member.
 * The chama admin approves or rejects it.
 */
@Entity
@Table(name = "loans")
public class Loan extends Model {

    @Id
    public Long id;

    /** The member who applied for the loan */
    @ManyToOne
    @JoinColumn(name = "user_id")
    public User user;

    /** Which chama the loan comes from */
    @ManyToOne
    @JoinColumn(name = "chama_id")
    public Chama chama;

    public Double amount;

    /** Interest rate as a percentage, e.g. 10.0 means 10% */
    @Column(name = "interest_rate")
    public Double interestRate;

    /** Total owed = amount + interest */
    @Column(name = "total_repayable")
    public Double totalRepayable;

    /** How much has been paid back so far */
    @Column(name = "amount_repaid")
    public Double amountRepaid;

    /**
     * Loan status:
     *   "pending"   — applied, waiting for admin
     *   "approved"  — admin approved, money disbursed
     *   "rejected"  — admin rejected
     *   "repaid"    — fully paid back
     *   "defaulted" — missed repayment deadline
     */
    public String status;

    @Column(name = "due_date")
    public LocalDateTime dueDate;

    @Column(name = "applied_at")
    public LocalDateTime appliedAt;

    @Column(name = "approved_at")
    public LocalDateTime approvedAt;

    public static final Finder<Long, Loan> find = new Finder<>(Loan.class);

    public static List<Loan> findByUser(Long userId) {
        return find.query().where().eq("user.id", userId).findList();
    }

    public static List<Loan> findPendingForChama(Long chamaId) {
        return find.query()
                .where()
                .eq("chama.id", chamaId)
                .eq("status", "pending")
                .findList();
    }

    public static List<Loan> findActiveForUser(Long userId) {
        return find.query()
                .where()
                .eq("user.id", userId)
                .eq("status", "approved")
                .findList();
    }

    /** Balance remaining on a loan */
    public Double balanceRemaining() {
        double repaid = amountRepaid != null ? amountRepaid : 0.0;
        double total  = totalRepayable != null ? totalRepayable : amount;
        return total - repaid;
    }
}