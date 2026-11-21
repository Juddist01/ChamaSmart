package models;

import io.ebean.*;
import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "loans")
public class Loan extends Model {

    @Id
    public Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    public User user;

    @ManyToOne
    @JoinColumn(name = "chama_id")
    public Chama chama;

    @Column(name = "user_name")
    public String userName;

    public Double amount;

    @Column(name = "interest_rate")
    public Double interestRate;

    @Column(name = "total_to_pay")
    public Double totalRepayable;

    @Column(name = "balance_remaining")
    public Double remainingBalance;

    @Column(name = "approval_status")
    public String status;

    @Column(name = "repayment_status")
    public String repaymentStatus;

    @Column(name = "credit_score")
    public Integer creditScore;

    @Column(name = "score_reason")
    public String scoreReason;

    @Column(name = "status")
    public String recordStatus;

    public static final Finder<Long, Loan> find =
            new Finder<>(Loan.class);

    public static List<Loan> findByUser(Long userId) {
        return find.query()
                .where()
                .eq("user.id", userId)
                .findList();
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

    public Double balanceRemaining() {
        return remainingBalance != null
                ? remainingBalance
                : (totalRepayable != null ? totalRepayable : amount);
    }
}
