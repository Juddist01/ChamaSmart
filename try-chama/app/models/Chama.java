package models;

import io.ebean.*;
import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "chama")
public class Chama extends Model {

    @Id
    @Column(name = "chama_id")
    public Long id;

    public String name;

    public String description;

    @ManyToOne
    @JoinColumn(name = "admin_id")
    public User admin;

    @ManyToMany
    @JoinTable(
            name = "chama_members",
            joinColumns = @JoinColumn(
                    name = "chama_id",
                    referencedColumnName = "chama_id"
            ),
            inverseJoinColumns = @JoinColumn(
                    name = "user_id",
                    referencedColumnName = "id"
            )
    )
    public List<User> members;

    @Column(name = "monthly_contribution_amount")
    public Double monthlyContribution;

    @Column(name = "created_at")
    public java.time.LocalDateTime createdAt;

    public static final Finder<Long, Chama> find =
            new Finder<>(Chama.class);

    public static Chama findById(Long id) {
        return find.byId(id);
    }

    public static List<Chama> findByAdmin(Long adminId) {
        return find.query()
                .where()
                .eq("admin.id", adminId)
                .findList();
    }

    public static List<Chama> findByMember(Long userId) {
        return find.query()
                .where()
                .eq("members.id", userId)
                .findList();
    }
}
