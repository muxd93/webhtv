package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.SmbServer;

import java.util.List;

@Dao
public abstract class SmbServerDao extends BaseDao<SmbServer> {

    @Query("SELECT * FROM SmbServer ORDER BY createTime ASC")
    public abstract List<SmbServer> findAll();

    @Query("SELECT * FROM SmbServer WHERE id = :id")
    public abstract SmbServer find(String id);

    @Query("DELETE FROM SmbServer WHERE id = :id")
    public abstract void delete(String id);

    @Query("DELETE FROM SmbServer")
    public abstract void deleteAll();
}
