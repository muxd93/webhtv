package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Transaction;

import com.fongmi.android.tv.bean.ElderCard;

import java.util.List;

@Dao
public abstract class ElderCardDao extends BaseDao<ElderCard> {

    @Query("SELECT * FROM ElderCard ORDER BY sortOrder ASC, createTime ASC")
    public abstract List<ElderCard> findAll();

    @Query("SELECT * FROM ElderCard WHERE type = :type ORDER BY sortOrder ASC, createTime ASC")
    public abstract List<ElderCard> findByType(ElderCard.Type type);

    @Query("SELECT * FROM ElderCard WHERE id = :id")
    public abstract ElderCard find(String id);

    @Query("DELETE FROM ElderCard WHERE id = :id")
    public abstract void delete(String id);

    @Query("DELETE FROM ElderCard")
    public abstract void deleteAll();

    @Query("UPDATE ElderCard SET sortOrder = :order WHERE id = :id")
    public abstract void updateSortOrder(String id, int order);

    @Transaction
    public void deleteAndReorder(String id) {
        delete(id);
        List<ElderCard> cards = findAll();
        for (int i = 0; i < cards.size(); i++) {
            updateSortOrder(cards.get(i).getId(), i);
        }
    }
}
