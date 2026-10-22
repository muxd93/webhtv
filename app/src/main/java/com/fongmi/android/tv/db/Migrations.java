package com.fongmi.android.tv.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

public class Migrations {

    public static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL, `adaptive` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE History_Backup (`key` TEXT NOT NULL, `vodPic` TEXT, `vodName` TEXT, `vodFlag` TEXT, `vodRemarks` TEXT, `episodeUrl` TEXT, `revSort` INTEGER NOT NULL, `revPlay` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `opening` INTEGER NOT NULL, `ending` INTEGER NOT NULL, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `speed` REAL NOT NULL, `scale` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`key`))");
            database.execSQL("INSERT INTO History_Backup SELECT `key`, `vodPic`, `vodName`, `vodFlag`, `vodRemarks`, `episodeUrl`, `revSort`, `revPlay`, `createTime`, `opening`, `ending`, `position`, `duration`, `speed`, `scale`, `cid` FROM History");
            database.execSQL("DROP TABLE History");
            database.execSQL("ALTER TABLE History_Backup RENAME to History");
        }
    };

    public static final Migration MIGRATION_32_33 = new Migration(32, 33) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Live ADD COLUMN keep TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_33_34 = new Migration(33, 34) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_34_35 = new Migration(34, 35) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `format` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_35_36 = new Migration(35, 36) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE History ADD COLUMN wallPic TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_36_37 = new Migration(36, 37) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS PlaybackDeleteTombstone (`id` TEXT NOT NULL, `configKey` TEXT NOT NULL, `scope` TEXT NOT NULL, `historyKey` TEXT NOT NULL, `siteKey` TEXT NOT NULL, `vodId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_PlaybackDeleteTombstone_deletedAt` ON `PlaybackDeleteTombstone` (`deletedAt`)");
        }
    };

    /**
     * 唯一迁移 37→38（源模型升级总装）：
     * Config 增加 kind/active/etag/lastModified 并激活各类型最近使用的行；
     * 新增 ConfigDepot 多仓子源关系表；
     * History/Keep 主键改 (cid,key)；Track 增加 cid、唯一索引改 (cid,key,type)。
     * 项目未发布、无历史数据，故不做分步迁移与数据自愈。
     */
    /**
     * 38→39：新增 DownloadTask 离线缓存任务表（列序与实体声明序一致，Room 校验 schema）。
     */
    public static final Migration MIGRATION_38_39 = new Migration(38, 39) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `DownloadTask` (`id` TEXT NOT NULL, `cid` INTEGER NOT NULL, `siteKey` TEXT, `vodId` TEXT, `vodName` TEXT, `vodPic` TEXT, `vodFlag` TEXT, `episodeName` TEXT, `episodeUrl` TEXT, `kind` INTEGER NOT NULL, `state` INTEGER NOT NULL, `dirPath` TEXT, `fileName` TEXT, `totalBytes` INTEGER NOT NULL, `doneBytes` INTEGER NOT NULL, `totalSegments` INTEGER NOT NULL, `doneSegments` INTEGER NOT NULL, `resolvedUrl` TEXT, `headers` TEXT, `createTime` INTEGER NOT NULL, `finishTime` INTEGER NOT NULL, `lastPlayTime` INTEGER NOT NULL, `errorMsg` TEXT, PRIMARY KEY(`id`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_DownloadTask_cid_siteKey_vodId` ON `DownloadTask` (`cid`, `siteKey`, `vodId`)");
        }
    };

    /**
     * 39→40：DownloadTask 增加 stopReason/retryCount/sourceHash 三列
     * （默认值与实体 @ColumnInfo(defaultValue) 一致，Room schema 校验才能通过）。
     */
    public static final Migration MIGRATION_39_40 = new Migration(39, 40) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `DownloadTask` ADD COLUMN `stopReason` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `DownloadTask` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `DownloadTask` ADD COLUMN `sourceHash` TEXT");
        }
    };

    public static final Migration MIGRATION_37_38 = new Migration(37, 38) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Config：源条目增强 + 显式激活
            database.execSQL("ALTER TABLE `Config` ADD COLUMN `kind` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `Config` ADD COLUMN `active` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `Config` ADD COLUMN `etag` TEXT");
            database.execSQL("ALTER TABLE `Config` ADD COLUMN `lastModified` TEXT");
            database.execSQL("UPDATE `Config` SET `active` = 1 WHERE `type` = 0 AND `id` = (SELECT `id` FROM `Config` WHERE `type` = 0 ORDER BY `time` DESC LIMIT 1)");
            database.execSQL("UPDATE `Config` SET `active` = 1 WHERE `type` = 1 AND `id` = (SELECT `id` FROM `Config` WHERE `type` = 1 ORDER BY `time` DESC LIMIT 1)");
            database.execSQL("UPDATE `Config` SET `active` = 1 WHERE `type` = 2 AND `id` = (SELECT `id` FROM `Config` WHERE `type` = 2 ORDER BY `time` DESC LIMIT 1)");
            database.execSQL("CREATE TABLE `ConfigDepot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `configId` INTEGER NOT NULL, `url` TEXT, `name` TEXT, `ord` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ConfigDepot_configId_url` ON `ConfigDepot` (`configId`, `url`)");
            // History：PK(key) → PK(cid,key)
            database.execSQL("CREATE TABLE History_Backup (`key` TEXT NOT NULL, `vodPic` TEXT, `wallPic` TEXT, `vodName` TEXT, `vodFlag` TEXT, `vodRemarks` TEXT, `episodeUrl` TEXT, `revSort` INTEGER NOT NULL, `revPlay` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `opening` INTEGER NOT NULL, `ending` INTEGER NOT NULL, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `speed` REAL NOT NULL, `scale` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`cid`, `key`))");
            database.execSQL("INSERT INTO History_Backup SELECT `key`, `vodPic`, `wallPic`, `vodName`, `vodFlag`, `vodRemarks`, `episodeUrl`, `revSort`, `revPlay`, `createTime`, `opening`, `ending`, `position`, `duration`, `speed`, `scale`, `cid` FROM History");
            database.execSQL("DROP TABLE History");
            database.execSQL("ALTER TABLE History_Backup RENAME to History");
            // Keep：PK(key) → PK(cid,key)
            database.execSQL("CREATE TABLE Keep_Backup (`key` TEXT NOT NULL, `siteName` TEXT, `vodName` TEXT, `vodPic` TEXT, `createTime` INTEGER NOT NULL, `type` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`cid`, `key`))");
            database.execSQL("INSERT INTO Keep_Backup SELECT `key`, `siteName`, `vodName`, `vodPic`, `createTime`, `type`, `cid` FROM Keep");
            database.execSQL("DROP TABLE Keep");
            database.execSQL("ALTER TABLE Keep_Backup RENAME to Keep");
            // Track：增加 cid，唯一索引 (key,type) → (cid,key,type)
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `format` TEXT, `selected` INTEGER NOT NULL, `cid` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_cid_key_type` ON `Track` (`cid`, `key`, `type`)");
            // Site：PK(key) → PK(sourceId,key)，站点偏好（可搜索/可切换）按源隔离
            database.execSQL("DROP TABLE Site");
            database.execSQL("CREATE TABLE Site (`sourceId` INTEGER NOT NULL, `key` TEXT NOT NULL, `searchable` INTEGER, `changeable` INTEGER, PRIMARY KEY(`sourceId`, `key`))");
            // Live：PK(name) → PK(sourceId,name)
            database.execSQL("DROP TABLE Live");
            database.execSQL("CREATE TABLE Live (`sourceId` INTEGER NOT NULL, `name` TEXT NOT NULL, `keep` TEXT, `boot` INTEGER NOT NULL, `pass` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `name`))");
        }
    };
}
