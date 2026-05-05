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

        db.execSQL(
            """
            CREATE TABLE chat_threads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                llm_model TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                thread_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(thread_id) REFERENCES chat_threads(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE session_wrapup_jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL UNIQUE,
                status TEXT NOT NULL DEFAULT 'pending',
                current_step TEXT,
                step_detail TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                llm_model TEXT,
                enqueued_at INTEGER NOT NULL,
                started_at INTEGER,
                finished_at INTEGER,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE session_summaries (
                session_id INTEGER PRIMARY KEY,
                generated_title TEXT,
                theme TEXT,
                agenda_json TEXT,
                llm_provider TEXT,
                llm_model TEXT,
                generated_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
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
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN error_message TEXT")
            db.execSQL(
                "UPDATE sessions SET status = 'error' WHERE status = 'interrupted'"
            )
        }
        if (oldVersion < 4) {
            db.execSQL(
                """
                CREATE TABLE chat_threads (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    llm_model TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    thread_id INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    text TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(thread_id) REFERENCES chat_threads(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 5) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS session_wrapup_jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL UNIQUE,
                    status TEXT NOT NULL DEFAULT 'pending',
                    current_step TEXT,
                    step_detail TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    llm_model TEXT,
                    enqueued_at INTEGER NOT NULL,
                    started_at INTEGER,
                    finished_at INTEGER,
                    FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS session_summaries (
                    session_id INTEGER PRIMARY KEY,
                    generated_title TEXT,
                    theme TEXT,
                    agenda_json TEXT,
                    llm_provider TEXT,
                    llm_model TEXT,
                    generated_at INTEGER NOT NULL,
                    FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }
    }

    companion object {
        fun buildSessionTitle(startedAt: Long): String {
            val format = SimpleDateFormat("M月d日(E) HH時mm分", Locale.JAPAN)
            return "${format.format(Date(startedAt))}の議事録"
        }

        const val DATABASE_NAME = "xtrust-standalone.db"
        const val DATABASE_VERSION = 5
    }
}
