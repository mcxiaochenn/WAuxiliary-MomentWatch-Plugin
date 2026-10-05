package com.tencent.wcdb.database;
import android.content.ContentValues;
/**
 * 测试桩：模仿腾讯 WCDB 的 SQLiteDatabase 写入方法集合，
 * 用来验证插件能枚举并注册全部同名重载（共 8 个）。
 */
public class SQLiteDatabase {
    public long insert(String table, String nullColumnHack, ContentValues values) { return 1L; }
    public long insert(String table, String nullColumnHack, ContentValues values, int conflictAlgorithm) { return 2L; }
    public long insertOrThrow(String table, String nullColumnHack, ContentValues values) { return 3L; }
    public long insertWithOnConflict(String table, String nullColumnHack, ContentValues values, int conflictAlgorithm) { return 4L; }
    public long replace(String table, String nullColumnHack, ContentValues initialValues) { return 5L; }
    public long replaceOrThrow(String table, String nullColumnHack, ContentValues initialValues) { return 6L; }
    public int update(String table, ContentValues values, String whereClause, String[] whereArgs) { return 7; }
    public int updateWithOnConflict(String table, ContentValues values, String whereClause, String[] whereArgs, int conflictAlgorithm) { return 8; }
    public int delete(String table, String whereClause, String[] whereArgs) { return 9; }
}
