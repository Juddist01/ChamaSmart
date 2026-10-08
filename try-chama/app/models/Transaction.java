package models;

import io.ebean.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A Transaction is a financial event in the chama ledger.
 * Every contribution payment and loan disbursement/repayment
 * creates a Transaction — this is your audit trail.
 *
 * From your DB the type column is:
 *   enum('contribution', 'loan_disburse­ment', 'loan_repayment')
 */
@Entity
@Table(name = "transactions")
public class Transaction extends Model {

    @Id
    public Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    public User user;

    @ManyToOne
    @JoinColumn(name = "chama_id")
    public Chama chama;

    /**
     * Type of transaction:
     *   "contribution"    — member paid their monthly amount
     *   "loan_disbursement" — loan money sent to member
     *   "loan_repayment"  — member paying back a loan
     */
    public String type;

    public Double amount;

    @Column(name = "reference_id")
    public Long referenceId;    // ID of the Contribution or Loan this relates to

    public String description;  // human-readable note, e.g. "March contribution"

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    public static final Finder<Long, Transaction> find = new Finder<>(Transaction.class);

    public static List<Transaction> findByChama(Long chamaId) {
        return find.query()
                .where()
                .eq("chama.id", chamaId)
                .orderBy("createdAt desc")
                .findList();
    }

    public static List<Transaction> findByUser(Long userId) {
        return find.query()
                .where()
                .eq("user.id", userId)
                .orderBy("createdAt desc")
                .findList();
    }

    /** Convenience: record a transaction in one call */
    public static void record(User user, Chama chama, String type,
                              Double amount, Long referenceId, String description) {
        Transaction t = new Transaction();
        t.user        = user;
        t.chama       = chama;
        t.type        = type;
        t.amount      = amount;
        t.referenceId = referenceId;
        t.description = description;
        t.createdAt   = LocalDateTime.now();
        t.save();
    }
}