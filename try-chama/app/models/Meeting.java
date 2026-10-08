package models;

import io.ebean.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A Meeting is a scheduled chama gathering.
 * The admin creates it; members can mark attendance.
 */
@Entity
@Table(name = "meetings")
public class Meeting extends Model {

    @Id
    public Long id;

    @ManyToOne
    @JoinColumn(name = "chama_id")
    public Chama chama;

    public String title;        // e.g. "March Monthly Meeting"
    public String agenda;       // what will be discussed
    public String minutes;      // notes recorded after the meeting
    public String venue;        // physical location or "Online"

    @Column(name = "meeting_date")
    public LocalDateTime meetingDate;

    /**
     * Meeting status:
     *   "scheduled" — upcoming
     *   "completed" — already happened
     *   "cancelled" — called off
     */
    public String status;

    public static final Finder<Long, Meeting> find = new Finder<>(Meeting.class);

    public static List<Meeting> findByChama(Long chamaId) {
        return find.query().where().eq("chama.id", chamaId).findList();
    }

    public static List<Meeting> findUpcoming(Long chamaId) {
        return find.query()
                .where()
                .eq("chama.id", chamaId)
                .eq("status", "scheduled")
                .gt("meetingDate", LocalDateTime.now())
                .orderBy("meetingDate asc")
                .findList();
    }
}