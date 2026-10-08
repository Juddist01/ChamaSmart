package models;

import io.ebean.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A Contribution is a payment a member makes to their chama.
 * e.g. "Peter paid Ksh 6,000 for March"
 */
@Entity
@Table(name = "contributions")
public class Contribution extends Model {

    @Id
    public Long id;

    /** Which member made this contribution */
    @ManyToOne
    @JoinColumn(name = "user_id")
    public User user;

    /** Which chama it was paid into */
    @ManyToOne
    @JoinColumn(name = "chama_id")
    public Chama chama;

    public Double amount;

    /** e.g. "March 2026" — the cycle this covers */
    public String period;

    /**
     * Payment status:
     *   "pending"  — recorded but not confirmed
     *   "paid"     — confirmed by admin
     *   "missed"   — deadline passed with no payment
     */
    public String status;

    @Column(name = "paid_at")
    public LocalDateTime paidAt;

    public static final Finder<Long, Contribution> find = new Finder<>(Contribution.class);

    /** All contributions for a chama */
    public static List<Contribution> findByChama(Long chamaId) {
        return find.query().where().eq("chama.id", chamaId).findList();
    }

    /** All contributions by a specific member */
    public static List<Contribution> findByUser(Long userId) {
        return find.query().where().eq("user.id", userId).findList();
    }

    /** Total amount contributed to a chama */
    public static Double totalForChama(Long chamaId) {
        return find.query()
                .where()
                .eq("chama.id", chamaId)
                .eq("status", "paid")
                .findList()
                .stream()
                .mapToDouble(c -> c.amount)
                .sum();
    }
}