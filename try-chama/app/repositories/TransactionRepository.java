package repositories;

import models.Transaction;
import jakarta.inject.Singleton;
import java.util.List;

@Singleton
public class TransactionRepository {

    /** All transactions for a specific user, newest first */
    public List<Transaction> findByUser(Long userId) {
        return Transaction.find.query()
                .where()
                .eq("user.id", userId)
                .orderBy("createdAt desc")
                .setMaxRows(50)
                .findList();
    }

    /** All transactions for a chama, newest first */
    public List<Transaction> findByChama(Long chamaId) {
        return Transaction.find.query()
                .where()
                .eq("chama.id", chamaId)
                .orderBy("createdAt desc")
                .setMaxRows(100)
                .findList();
    }

    /** Recent N transactions for a user — used on dashboard */
    public List<Transaction> findRecentByUser(Long userId, int limit) {
        return Transaction.find.query()
                .where()
                .eq("user.id", userId)
                .orderBy("createdAt desc")
                .setMaxRows(limit)
                .findList();
    }

    /** Save a new transaction record */
    public void save(Transaction transaction) {
        transaction.save();
    }
}