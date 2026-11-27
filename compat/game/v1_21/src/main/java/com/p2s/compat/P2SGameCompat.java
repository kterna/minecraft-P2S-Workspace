package com.p2s.compat;

import net.minecraft.Util;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;

/** Game, registry, blockstate, and player identity helpers for MC 1.21-1.21.1. */
public final class P2SGameCompat {
    private P2SGameCompat() {
    }

    public static String normalizeResourceId(String raw) {
        ResourceLocation id = parseResourceId(raw);
        return id == null ? null : id.toString();
    }

    private static ResourceLocation parseResourceId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null && !raw.contains(":")) {
            id = ResourceLocation.tryParse("minecraft:" + raw);
        }
        return id;
    }

    public static Block findBlock(String rawId) {
        ResourceLocation id = parseResourceId(rawId);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    public static BlockState defaultBlockState(String rawId) {
        Block block = findBlock(rawId);
        return block == null ? null : block.defaultBlockState();
    }

    public static Iterable<String> allBlockIds() {
        List<String> ids = new ArrayList<>(BuiltInRegistries.BLOCK.keySet().size());
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            ids.add(id.toString());
        }
        return ids;
    }

    public static String blockId(BlockState state) {
        return state == null ? "" : blockId(state.getBlock());
    }

    public static String blockId(Block block) {
        return block == null ? "" : BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static String normalizeItemId(String rawItemId) {
        if (rawItemId == null || rawItemId.isBlank()) {
            return null;
        }
        String trimmed = rawItemId.trim().toLowerCase();
        ResourceLocation id = parseResourceId(trimmed);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        return id.toString();
    }

    public static boolean isItemWithId(Item item, String configuredId) {
        if (item == null) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(configuredId);
        if (id == null) {
            return false;
        }
        Item target = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return target != null && target == item;
    }

    public static ItemStack iconForBlock(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        return block == null ? ItemStack.EMPTY : new ItemStack(block.asItem());
    }

    public static BlockState parseBlockState(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state, false).blockState();
        } catch (Exception ex) {
            return null;
        }
    }

    public static String serializeBlockState(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    public static String playerName(Player player) {
        return player == null ? "" : player.getGameProfile().getName();
    }

    public static MinecraftServer serverOf(ServerPlayer player) {
        return player == null ? null : player.getServer();
    }

    @SuppressWarnings("unchecked")
    public static BlockState applyFacing(BlockState state, String facing) {
        if (state == null || facing == null || facing.isBlank()) {
            return state;
        }
        Direction dir = directionByName(facing.trim().toLowerCase());
        if (dir == null) {
            return state;
        }
        for (Property<?> property : state.getProperties()) {
            if (property.getValueClass() == Direction.class && property.getPossibleValues().contains(dir)) {
                return state.setValue((Property<Direction>) property, dir);
            }
        }
        return state;
    }

    public static Direction directionByName(String raw) {
        return Direction.byName(raw);
    }

    public static long millis() {
        return Util.getMillis();
    }
}
