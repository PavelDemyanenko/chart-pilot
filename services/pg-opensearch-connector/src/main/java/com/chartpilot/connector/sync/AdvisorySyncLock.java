package com.chartpilot.connector.sync;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Active/Standby-координация коннекторов (ARCH-03): pg_try_advisory_lock на цикл синхронизации.
 * Lock сессионный — удерживается выделенным соединением и снимается в unlock()
 * (закрытие соединения также автоматически снимает lock).
 */
@Component
public class AdvisorySyncLock {

    private static final Logger log = LoggerFactory.getLogger(AdvisorySyncLock.class);
    private static final long LOCK_KEY = 732_001_987L;

    private final DataSource dataSource;
    private Connection held;

    public AdvisorySyncLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public synchronized boolean tryLock() {
        if (held != null) {
            return false;
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT pg_try_advisory_lock(" + LOCK_KEY + ")")) {
                if (rs.next() && rs.getBoolean(1)) {
                    held = connection;
                    return true;
                }
            }
            connection.close();
            return false;
        } catch (SQLException e) {
            log.warn("Advisory lock acquisition failed: {}", e.getMessage());
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
            return false;
        }
    }

    public synchronized void unlock() {
        if (held == null) {
            return;
        }
        try (Statement statement = held.createStatement()) {
            statement.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
        } catch (SQLException e) {
            log.warn("Advisory unlock failed (connection close will release it): {}", e.getMessage());
        } finally {
            try {
                held.close();
            } catch (SQLException ignored) {
            }
            held = null;
        }
    }
}