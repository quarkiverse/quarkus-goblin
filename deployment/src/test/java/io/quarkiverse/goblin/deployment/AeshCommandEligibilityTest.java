package io.quarkiverse.goblin.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.MethodInfo;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.internal.config.TargetRules;

/**
 * Aesh CLI commands are never decorated with the service-assault binding: the ArC subclass of an intercepted command
 * does not carry {@code @CommandDefinition}, and Aesh refuses it, so the CLI never starts (#95).
 */
class AeshCommandEligibilityTest {

    private static final TargetRules NO_RULES = new TargetRules(List.of(), List.of(), List.of());

    @CommandDefinition(name = "ping", description = "ping")
    public static class PingCommand implements Command<CommandInvocation> {
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "folder", description = "folder", groupCommands = PingCommand.class)
    public static class FolderCommand implements Command<CommandInvocation> {
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            return CommandResult.SUCCESS;
        }
    }

    public abstract static class BaseCommand implements Command<CommandInvocation> {
    }

    public static class InheritedCommand extends BaseCommand {
        @Override
        public CommandResult execute(CommandInvocation invocation) {
            return CommandResult.SUCCESS;
        }
    }

    public static class PlainService {
        public String work() {
            return "work";
        }
    }

    @Test
    void aeshCommandsAreNeverDecorated() throws IOException {
        Index index = Index.of(PingCommand.class, FolderCommand.class, BaseCommand.class, InheritedCommand.class,
                PlainService.class);

        assertFalse(eligible(index, PingCommand.class, "execute"), "@CommandDefinition command");
        assertFalse(eligible(index, FolderCommand.class, "execute"), "group command");
        assertFalse(eligible(index, InheritedCommand.class, "execute"), "command through its super class");
        assertTrue(eligible(index, PlainService.class, "work"), "a plain service stays eligible");
    }

    private static boolean eligible(Index index, Class<?> type, String methodName) {
        ClassInfo clazz = index.getClassByName(DotName.createSimple(type.getName()));
        MethodInfo method = clazz.firstMethod(methodName);
        return GoblinBuildStep.isMethodEligible(clazz, method, NO_RULES, index);
    }
}
