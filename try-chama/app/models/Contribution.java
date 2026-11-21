package models;

import io.ebean.*;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "contributions")
public class Contribution extends Model {

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

    @Column(name = "chama_name")
    public String chamaName;

    @Column(name = "amount_paid")
    public Double amount;

    @Column(name = "expected_amount")
    public Double expectedAmount;

    @Column(name = "period_name")
    public String period;

    public String status;

    @Column(name = "due_date")
    public LocalDate dueDate;

    @Column(name = "payment_date")
    public LocalDateTime paidAt;

    @Column(name = "payment_method")
    public String paymentMethod;

    public static final Finder<Long, Contribution> find =
            new Finder<>(Contribution.class);

    public static List<Contribution> findByChama(Long chamaId) {
        return find.query()
                .where()
                .eq("chama.id", chamaId)
                .findList();
    }

    public static List<Contribution> findByUser(Long userId) {
        return find.query()
                .where()
                .eq("user.id", userId)
                .findList();
    }

    public static Double totalForChama(Long chamaId) {
        return find.query()
                .where()
                .eq("chama.id", chamaId)
                .eq("status", "paid")
                .findList()
                .stream()
                .mapToDouble(c -> c.amount != null ? c.amount : 0.0)
                .sum();
    }
}
