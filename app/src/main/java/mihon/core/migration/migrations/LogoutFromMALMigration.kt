package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.lang.withIOContext

class LogoutFromMALMigration : Migration {
    override val version: Float = 12f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        return@withIOContext true
    }
}
