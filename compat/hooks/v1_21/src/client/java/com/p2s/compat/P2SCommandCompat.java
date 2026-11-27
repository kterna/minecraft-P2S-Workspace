package com.p2s.compat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.p2s.P2SI18n;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Client-side Brigadier command registration helpers for MC 1.21+. */
public final class P2SCommandCompat {
    private P2SCommandCompat() {
    }

    public static void registerExportCommand(Runnable exportAction) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
                ClientCommandManager.literal("p2s").then(ClientCommandManager.literal("export").executes(ctx -> {
                    exportAction.run();
                    return 1;
                }))));
    }

    public static void registerThemeCommand(
            IntSupplier reloadAction,
            Predicate<String> selectAction,
            Supplier<List<String>> availableIds,
            Supplier<String> currentThemeName
    ) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
                ClientCommandManager.literal("p2s").then(ClientCommandManager.literal("theme")
                        .then(ClientCommandManager.literal("reload").executes(ctx -> {
                            int errors = reloadAction.getAsInt();
                            ctx.getSource().sendFeedback(P2SI18n.tr("message.p2s.theme.reloaded",
                                    availableIds.get().size(), errors));
                            return errors == 0 ? 1 : 0;
                        }))
                        .then(ClientCommandManager.argument("id", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        availableIds.get().stream(), builder))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    if (!selectAction.test(id)) {
                                        ctx.getSource().sendError(P2SI18n.tr("message.p2s.theme.unknown", id));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(P2SI18n.tr("message.p2s.theme.selected",
                                            currentThemeName.get()));
                                    return 1;
                                })))));
    }
}
