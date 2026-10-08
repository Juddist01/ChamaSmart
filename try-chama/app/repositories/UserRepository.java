package repositories;

import io.ebean.*;
import io.ebean.DB;
import models.User;
import java.util.Optional;


public class UserRepository {

    public void save(User user) {
        DB.save(user);
    }

    public void update(User user) {
        user.save();
    }


    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(User.find.query().where().eq("email", email).findOne());
    }


    public Optional<User> findById(Long id) {
        return Optional.ofNullable(User.find.byId(id));
    }


}