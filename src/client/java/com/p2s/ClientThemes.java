package com.p2s;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.p2s.theme.P2STheme;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Connects the client configuration, commands and chat to the theme catalogue. */
public final class ClientThemes {
    private static final List<Component> startupMessages = new ArrayList<>();

    private ClientThemes() {}

    static void initialize() {
        reload();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
                ClientCommandManager.literal("p2s").then(ClientCommandManager.literal("theme")
                        .then(ClientCommandManager.literal("reload").executes(ctx -> {
                            int errors = reload();
                            ctx.getSource().sendFeedback(P2SI18n.tr("message.p2s.theme.reloaded",
                                    P2STheme.available().size(), errors));
                            return errors == 0 ? 1 : 0;
                        }))
                        .then(ClientCommandManager.argument("id", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        P2STheme.available().stream().map(P2STheme::id), builder))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    if (!select(id)) {
                                        ctx.getSource().sendError(P2SI18n.tr("message.p2s.theme.unknown", id));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(P2SI18n.tr("message.p2s.theme.selected", P2STheme.current().name()));
                                    return 1;
                                })))));
    }

    public static boolean select(String id) {
        if (!P2STheme.select(id)) return false;
        P2SClientConfig.setThemeId(id);
        return true;
    }

    private static int reload() {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("p2s_themes");
        String requested = P2SClientConfig.getThemeId();
        List<String> errors = new ArrayList<>();
        P2STheme.reload(directory, requested, errors::add);
        if (requested.isBlank()) P2SClientConfig.setThemeId(P2STheme.current().id());
        for (String reason : errors) {
            P2SMod.LOGGER.warn("P2S theme: {}", reason);
            startupMessages.add(P2SI18n.tr("message.p2s.theme.invalid", reason));
        }
        flushMessages(Minecraft.getInstance());
        return errors.size();
    }

    static void flushMessages(Minecraft client) {
        if (client == null || client.player == null || startupMessages.isEmpty()) return;
        for (Component message : startupMessages) {
            client.gui.getChat().addMessage(message);
            ClientSessionState.addSystemMessage(message.getString());
        }
        startupMessages.clear();
    }
}
