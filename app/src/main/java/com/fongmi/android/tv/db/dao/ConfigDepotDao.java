package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.ConfigDepot;

import java.util.List;

@Dao
public abstract class ConfigDepotDao extends BaseDao<ConfigDepot> {

    @Query("SELECT * FROM ConfigDepot")
    public abstract List<ConfigDepot> findAll();

    @Query("SELECT * FROM ConfigDepot WHERE configId = :configId ORDER BY ord")
    public abstract List<ConfigDepot> findByConfig(int configId);

    @Query("DELETE FROM ConfigDepot WHERE configId = :configId")
    public abstract void deleteByConfig(int configId);

    @Query("DELETE FROM ConfigDepot")
    public abstract void delete();
}
