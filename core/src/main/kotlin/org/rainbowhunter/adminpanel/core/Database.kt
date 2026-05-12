package org.rainbowhunter.adminpanel.core

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

fun buildDataSource(jdbcUrl: String): HikariDataSource = HikariDataSource(HikariConfig().apply {
    this.jdbcUrl = jdbcUrl
    maximumPoolSize = 5
})

fun runMigrations(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}

fun connectExposed(dataSource: DataSource) {
    Database.connect(dataSource)
}
