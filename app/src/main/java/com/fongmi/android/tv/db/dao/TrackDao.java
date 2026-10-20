package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fongmi.android.tv.bean.Track;

import java.util.List;

@Dao
public abstract class TrackDao extends BaseDao<Track> {

    @Query("SELECT * FROM Track")
    public abstract List<Track> findAll();

    @Query("SELECT * FROM Track WHERE cid = :cid AND `key` = :key")
    public abstract List<Track> find(int cid, String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract Long insert(Track item);

    @Query("DELETE FROM Track WHERE cid = :cid AND `key` = :key")
    public abstract void delete(int cid, String key);

    @Query("DELETE FROM Track")
    public abstract void deleteAll();
}
