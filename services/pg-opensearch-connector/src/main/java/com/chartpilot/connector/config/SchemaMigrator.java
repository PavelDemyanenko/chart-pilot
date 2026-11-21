package com.chartpilot.connector.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Flyway — единственный владелец схемы PG. Выполняется при инициализации
 * контекста, до первого тика @Scheduled SyncService.
 */
@Component
public class SchemaMigrator implements InitializingBean {

    private final DataSource dataSource;

    public SchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}