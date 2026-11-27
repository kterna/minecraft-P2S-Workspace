package com.p2s;

import com.p2s.compat.P2SCommandCompat;
import com.p2s.theme.P2STheme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
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
        P2SCommandCompat.registerThemeCommand(
                ClientThemes::reload,
                ClientThemes::select,
                () -> P2STheme.available().stream().map(P2STheme::id).toList(),
                () -> P2STheme.current().name()
        );
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
