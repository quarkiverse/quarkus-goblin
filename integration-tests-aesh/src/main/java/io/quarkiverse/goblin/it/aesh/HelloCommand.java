package io.quarkiverse.goblin.it.aesh;

import jakarta.inject.Inject;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;

@CommandDefinition(name = "hello", description = "Greets through a service bean")
public class HelloCommand implements Command<CommandInvocation> {

    @Argument(required = true)
    String name;

    @Inject
    GreetingService greetings;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        invocation.println(greetings.greet(name));
        return CommandResult.SUCCESS;
    }
}
