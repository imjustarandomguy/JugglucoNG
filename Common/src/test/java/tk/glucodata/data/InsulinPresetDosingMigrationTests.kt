package tk.glucodata.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v32 -> v33 step on a real SQLite, without Robolectric: the journal_insulin_presets table
 * is built from the committed v32 schema, the production ALTER statements are run on it, and the
 * result must be exactly the committed v33 table, the shape Room validates on open. The
 * Robolectric MigrationTestHelper runs (HistoryMigrationTest) cover the whole chain where they
 * can run.
 */
class InsulinPresetDosingMigrationTests {
    private fun file(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            File(directory, "Common/$relativePath").takeIf { it.isFile }?.let { return it }
            File(directory, relativePath).takeIf { it.isFile }?.let { return it }
            directory = directory.parentFile
        }
        error("Could not locate $relativePath")
    }

    private fun presetTable(version: Int): JSONObject {
        val entities = JSONObject(file("schemas/tk.glucodata.data.HistoryDatabase/$version.json").readText())
            .getJSONObject("database")
            .getJSONArray("entities")
        return (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .first { it.getString("tableName") == "journal_insulin_presets" }
    }

    /** The SQL MIGRATION_32_33 runs, in order, read from the production source. */
    private fun migrationStatements(): List<String> {
        val block = file("src/mobile/java/tk/glucodata/data/HistoryDatabase.kt").readText()
            .substringAfter("private val MIGRATION_32_33")
            .substringBefore("internal val ALL_MIGRATIONS")
        return Regex("\"((?:ALTER TABLE|UPDATE) [^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    private data class Column(val type: String, val notNull: Boolean, val defaultValue: String?)

    private fun committedColumns(version: Int): Map<String, Column> {
        val fields = presetTable(version).getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it) }.associate { field ->
            field.getString("columnName") to Column(
                type = field.getString("affinity"),
                notNull = field.optBoolean("notNull", false),
                defaultValue = if (field.has("defaultValue")) field.getString("defaultValue") else null
            )
        }
    }

    private fun Connection.columns(): Map<String, Column> =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(journal_insulin_presets)").use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(
                            rows.getString("name"),
                            Column(rows.getString("type"), rows.getInt("notnull") == 1, rows.getString("dflt_value"))
                        )
                    }
                }
            }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun v32Database(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
            db.exec(presetTable(32).getString("createSql").replace("\${TABLE_NAME}", "journal_insulin_presets"))
            db.exec(
                "INSERT INTO journal_insulin_presets (id, displayName, onsetMinutes, durationMinutes, accentColor, " +
                    "curveJson, isBuiltIn, isArchived, countsTowardIob, sortOrder) " +
                    "VALUES (6, 'Fiasp', 10, 300, 1, 'curve', 1, 0, 1, 6)"
            )
        }
    }

    @Test
    fun theStepAddsExactlyTheThreeDosingColumns() {
        val statements = migrationStatements().filter { it.startsWith("ALTER TABLE") }
        assertEquals(3, statements.size)
        assertTrue(statements.all { it.startsWith("ALTER TABLE journal_insulin_presets ADD COLUMN") })
    }

    @Test
    fun aV32TableBecomesTheCommittedV33Table() = v32Database().use { db ->
        assertEquals(committedColumns(32), db.columns())
        migrationStatements().forEach { db.exec(it) }
        assertEquals(committedColumns(33), db.columns())
    }

    @Test
    fun existingPresetsKeepTheHalfUnitStepAndGetNoDefaultDoseOrReminders() = v32Database().use { db ->
        migrationStatements().forEach { db.exec(it) }
        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT doseStep, defaultDose, reminderTimes, displayName FROM journal_insulin_presets WHERE id = 6"
            ).use { row ->
                assertTrue(row.next())
                assertEquals(0.5, row.getDouble(1), 0.0)
                row.getDouble(2)
                assertTrue("no default dose, rather than 0", row.wasNull())
                assertEquals("", row.getString(3))
                assertEquals("Fiasp", row.getString(4))
            }
        }
    }
}
