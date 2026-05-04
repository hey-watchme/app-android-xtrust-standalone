package com.xtrust.standalone.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                status TEXT NOT NULL,
                error_message TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                audio_path TEXT NOT NULL,
                transcript TEXT,
                asr_provider TEXT NOT NULL,
                asr_model TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                size_bytes INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL,
                transcription_ms INTEGER,
                real_time_factor REAL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE topics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                start_at INTEGER NOT NULL,
                end_at INTEGER NOT NULL,
                llm_provider TEXT NOT NULL,
                llm_model TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version < 2) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN title TEXT")
            val updates = mutableListOf<Pair<Long, String>>()
            db.rawQuery(
                "SELECT id, started_at FROM sessions",
                emptyArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                    val startedAt = cursor.getLong(cursor.getColumnIndexOrThrow("started_at"))
                    updates += id to buildSessionTitle(startedAt)
                }
            }
            updates.forEach { (id, title) ->
                db.execSQL(
                    "UPDATE sessions SET title = ? WHERE id = ?",
                    arrayOf<Any>(title, id)
                )
            }
            db.execSQL("UPDATE sessions SET title = '議事録' WHERE title IS NULL")
            version = 2
        }
        if (version < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN error_message TEXT")
            db.execSQL(
                "UPDATE sessions SET status = 'error' WHERE status = 'interrupted'"
            )
            version = 3
        }
    }

    companion object {
        fun buildSessionTitle(startedAt: Long): String {
            val format = SimpleDateFormat("M月d日(E) HH時mm分", Locale.JAPAN)
            return "${format.format(Date(startedAt))}の議事録"
        }

        const val DATABASE_NAME = "xtrust-standalone.db"
        const val DATABASE_VERSION = 3
    }
}
