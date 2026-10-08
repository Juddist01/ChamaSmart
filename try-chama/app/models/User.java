package models;

import io.ebean.*;
import jakarta.persistence.*;

@Entity
@Table(name = "user")
public class User extends Model {

    @Id
    public Long id;

    public String fullname;   // matches DB column: fullname
    public String phoneno;    // matches DB column: phoneno
    public String email;      // matches DB column: email
    public String password;   // matches DB column: password

    @Column(name = "chama_name")
    public String chamaName;  // matches DB column: chama_name

    public String role;       // matches DB column: role

    public User() {}

    public User(String fullname, String phoneno, String email,
                String password, String chamaName, String role) {
        this.fullname  = fullname;
        this.phoneno   = phoneno;
        this.email     = email;
        this.password  = password;
        this.chamaName = chamaName;
        this.role      = role;
    }

    public static final Finder<Long, User> find = new Finder<>(User.class);

    /** Used by sign-in: find user by email + password */
    public static User authenticate(String email, String password) {
        return find.query()
                .where()
                .eq("email", email)
                .eq("password", password)
                .findOne();
    }

    /** Find by email alone (e.g. to check if already registered) */
    public static User findByEmail(String email) {
        return find.query()
                .where()
                .eq("email", email)
                .findOne();
    }
}