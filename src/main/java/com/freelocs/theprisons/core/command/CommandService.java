package com.freelocs.theprisons.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds the single {@code /prisons} command tree (alias {@code /theprisons}). Contributors add their sub-commands
 * at start-up; the tree is registered once per dispatcher by the Fabric bridge.
 */
public final class CommandService {
    public static final String[] ROOTS = {"prisons", "theprisons"};

    private final List<Consumer<LiteralArgumentBuilder<FabricClientCommandSource>>> contributors = new ArrayList<>();

    public void contribute(Consumer<LiteralArgumentBuilder<FabricClientCommandSource>> contributor) {
        contributors.add(contributor);
    }

    public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        for (String root : ROOTS) {
            LiteralArgumentBuilder<FabricClientCommandSource> builder = ClientCommandManager.literal(root);
            for (Consumer<LiteralArgumentBuilder<FabricClientCommandSource>> contributor : contributors) {
                contributor.accept(builder);
            }
            dispatcher.register(builder);
        }
    }
}
