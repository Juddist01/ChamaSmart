package models;

import io.ebean.*;
import jakarta.persistence.*;
import java.util.List;

/**
 * A Chama is a savings group.
 * One admin (User) creates it; many members (Users) belong to it.
 */
@Entity
@Table(name = "chama")
public class Chama extends Model {

    @Id
    public Long id;

    public String name;           // e.g. "Umoja Chama"
    public String description;

    /** The admin who owns/created this chama */
    @ManyToOne
    @JoinColumn(name = "admin_id")
    public User admin;

    /** All members who belong to this chama */
    @ManyToMany
    @JoinTable(
            name = "chama_members",
            joinColumns        = @JoinColumn(name = "chama_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    public List<User> members;

    @Column(name = "monthly_contribution")
    public Double monthlyContribution;  // target amount per cycle

    @Column(name = "created_at")
    public java.time.LocalDateTime createdAt;

    public static final Finder<Long, Chama> find = new Finder<>(Chama.class);

    public static Chama findById(Long id) {
        return find.byId(id);
    }

    public static List<Chama> findByAdmin(Long adminId) {
        return find.query().where().eq("admin.id", adminId).findList();
    }

    public static List<Chama> findByMember(Long userId) {
        return find.query()
                .where()
                .eq("members.id", userId)
                .findList();
    }
}