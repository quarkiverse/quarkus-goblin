package io.quarkiverse.goblin.it;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Plain JDBC repository of the integration-tests app, backed by the in-memory H2 datasource and only called through
 * {@link SampleService}. Every call acquires a connection from the Agroal pool, which is where the Goblin DATABASE layer
 * injects its faults.
 */
@ApplicationScoped
public class SampleRepository {

    private static final Logger LOG = Logger.getLogger(SampleRepository.class);

    @Inject
    DataSource dataSource;

    public int ping() {
        LOG.debugf("SampleRepository.ping() acquiring a JDBC connection");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT 1")) {
            result.next();
            return result.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("database unavailable", e);
        }
    }
}
