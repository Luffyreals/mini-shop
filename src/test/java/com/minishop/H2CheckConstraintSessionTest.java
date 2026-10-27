package com.minishop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Plain H2, no Spring: a CHECK constraint must keep working after the connection that created it is closed while
 * the database stays open. That is what a connection pool does when it retires its first connection (the one
 * Flyway ran the migrations on).
 */
class H2CheckConstraintSessionTest {

    private static final String OPTIONS = ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    @TempDir
    Path dir;

    @Test
    void inListCheck_inMemory_stillWorksAfterTheCreatingConnectionClosed() throws SQLException {
        String url = "jdbc:h2:mem:check-" + UUID.randomUUID() + OPTIONS + ";DB_CLOSE_DELAY=-1";
        insertAfterCreatorClosed(url, "status in ('A', 'B')");
    }

    @Test
    void inListCheck_inFile_stillWorksAfterTheCreatingConnectionClosed() throws SQLException {
        String url = "jdbc:h2:file:" + dir.resolve("db").toAbsolutePath() + OPTIONS;
        insertAfterCreatorClosed(url, "status in ('A', 'B')");
    }

    @Test
    void comparisonCheck_inFile_stillWorksAfterTheCreatingConnectionClosed() throws SQLException {
        String url = "jdbc:h2:file:" + dir.resolve("db").toAbsolutePath() + OPTIONS;
        insertAfterCreatorClosed(url, "status >= 'A'");
    }

    private static void insertAfterCreatorClosed(String url, String check) throws SQLException {
        try (Connection keeper = DriverManager.getConnection(url, "sa", "")) {
            try (Connection creator = DriverManager.getConnection(url, "sa", "");
                 Statement statement = creator.createStatement()) {
                statement.execute("create table t (status varchar(10) not null, constraint ck_t check (" + check + "))");
            }
            assertThatCode(() -> {
                try (Statement statement = keeper.createStatement()) {
                    statement.executeUpdate("insert into t (status) values ('A')");
                }
            }).doesNotThrowAnyException();
        }
    }
}
