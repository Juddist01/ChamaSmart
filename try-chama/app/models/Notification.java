package models;

import io.ebean.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A Notification is an in-app alert for a user.
 * e.g. "Your loan was approved", "Contribution due in 3 days"
 */
@Entity
@Table(name = "notifications")
public class Notification extends Model {

    @Id
    public Long id;

    /** Who this notification is for */
    @ManyToOne
    @JoinColumn(name = "user_id")
    public User user;

    /** Short message shown in the notification bell */
    public String message;

    /**
     * Category — lets us show different icons/colours:
     *   "contribution"  — payment related
     *   "loan"          — loan related
     *   "meeting"       — meeting reminder
     *   "system"        — general app message
     */
    public String type;

    /** false = unread (shows red dot), true = user has seen it */
    @Column(name = "is_read")
    public boolean isRead = false;

    @Column(name = "created_at")
    public LocalDateTime createdAt;

    public static final Finder<Long, Notification> find = new Finder<>(Notification.class);

    /** All unread notifications for a user */
    public static List<Notification> findUnread(Long userId) {
        return find.query()
                .where()
                .eq("user.id", userId)
                .eq("isRead", false)
                .orderBy("createdAt desc")
                .findList();
    }

    /** All notifications for a user (for the full notification page) */
    public static List<Notification> findAllForUser(Long userId) {
        return find.query()
                .where()
                .eq("user.id", userId)
                .orderBy("createdAt desc")
                .setMaxRows(50)
                .findList();
    }

    /** Helper to create and save a notification in one line */
    public static void send(User user, String type, String message) {
        Notification n = new Notification();
        n.user      = user;
        n.type      = type;
        n.message   = message;
        n.isRead    = false;
        n.createdAt = LocalDateTime.now();
        n.save();
    }
}