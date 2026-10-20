import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;

public class AuthFlywayRepair {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://localhost:5433/banking";
        String user = "dbadmin";
        String password = System.getenv("AUTH_REPAIR_DB_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                "AUTH_REPAIR_DB_PASSWORD environment variable is missing.");
        }

        try (Connection connection =
                 DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {

            if (!"banking".equals(connection.getCatalog())) {
                throw new IllegalStateException("Wrong database.");
            }

            try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM public.flyway_schema_history_auth")) {
                result.next();
                if (result.getInt(1) != 0) {
                    throw new IllegalStateException(
                        "Auth Flyway history is not empty.");
                }
            }

            try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM pg_catalog.pg_tables " +
                "WHERE schemaname = 'public' AND tablename IN " +
                "('users','roles','user_roles','refresh_tokens','user_sessions')")) {
                result.next();
                if (result.getInt(1) != 0) {
                    throw new IllegalStateException(
                        "Auth tables already exist. Migration cancelled.");
                }
            }
        }

        Flyway flyway = Flyway.configure()
            .dataSource(url, user, password)
            .schemas("public")
            .defaultSchema("public")
            .table("flyway_schema_history_auth")
            .locations("filesystem:01-auth-service/src/main/resources/db/migration")
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
            .load();

        System.out.println("Running auth Flyway migrations...");
        var result = flyway.migrate();
        System.out.println("Migrations executed: " +
            result.migrationsExecuted);
        System.out.println("Target version: " +
            result.targetSchemaVersion);
    }
}
