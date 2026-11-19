package io.quarkiverse.goblin.it.aesh;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import jakarta.inject.Inject;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

import io.agroal.api.AgroalDataSource;

@CommandDefinition(name = "query", description = "Reads from the database")
public class QueryCommand implements Command<CommandInvocation> {

    @Inject
    AgroalDataSource dataSource;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select 42")) {
            result.next();
            invocation.println("answer " + result.getInt(1));
            return CommandResult.SUCCESS;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
