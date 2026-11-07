package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.RoomWarnings;
import androidx.room.Transaction;

import com.fongmi.android.tv.bean.Config;

import java.util.List;

@Dao
public abstract class ConfigDao extends BaseDao<Config> {

    @Query("SELECT * FROM Config")
    public abstract List<Config> findAll();

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC")
    public abstract List<Config> findByType(int type);

    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH)
    @Query("SELECT id, name, url, type, time, kind, active FROM Config WHERE type = :type ORDER BY time DESC")
    public abstract List<Config> findUrlByType(int type);

    @Query("SELECT * FROM Config WHERE id = :id")
    public abstract Config findById(int id);

    @Query("SELECT * FROM Config WHERE type = :type AND active = 1 ORDER BY time DESC LIMIT 1")
    public abstract Config findActive(int type);

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC LIMIT 1")
    public abstract Config findLatest(int type);

    @Query("SELECT * FROM Config WHERE url = :url AND type = :type")
    public abstract Config find(String url, int type);

    @Query("UPDATE Config SET active = 0 WHERE type = :type AND id != :id")
    public abstract void clearActive(int type, int id);

    @Query("DELETE FROM Config WHERE url = :url AND type = :type")
    public abstract void delete(String url, int type);

    @Query("DELETE FROM Config")
    public abstract void delete();

    /** 事务内 upsert：唯一键冲突时回查补齐 id 后再 update，避免 insert/update 之间被其他写路径插入 */
    @Transaction
    public Config upsert(Config config) {
        long id = insert(config);
        if (id == -1) {
            Config current = find(config.getUrl(), config.getType());
            if (current == null && config.getId() > 0) current = findById(config.getId());
            if (current == null) return config;
            config.setId(current.getId());
            update(config);
        } else {
            config.setId(Math.toIntExact(id));
        }
        return config;
    }

    /** 事务内激活：置本条 active 与清同类型其他 active 原子完成，杜绝「零 active / 双 active」中间态 */
    @Transaction
    public Config activate(Config config) {
        upsert(config);
        if (config.getId() > 0) clearActive(config.getType(), config.getId());
        return config;
    }
}
