package dev.codex.pyscriptmod.bridge;

import dev.codex.pyscriptmod.PyScriptMod;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FabricMinecraftBridge implements MinecraftBridge {
    private final ServerCommandSource source;

    public FabricMinecraftBridge(ServerCommandSource source) {
        this.source = source;
    }

    @Override
    public void say(String message) {
        source.getServer().getPlayerManager().broadcast(Text.literal(message), false);
        source.sendFeedback(() -> Text.literal("[PyScript] " + message), false);
    }

    @Override
    public void log(String message) {
        PyScriptMod.LOGGER.info("[PyScript] {}", message);
        source.sendFeedback(() -> Text.literal("[PyScript] " + message), false);
    }

    @Override
    public ScriptEntity summon(String entityId, ScriptPos pos) {
        ServerWorld serverWorld = world();
        net.minecraft.entity.EntityType<?> type = Registries.ENTITY_TYPE.get(Identifier.of(entityId));
        Entity entity = type.create(serverWorld, SpawnReason.COMMAND);
        if (entity == null) {
            throw new IllegalStateException("Could not create entity: " + entityId);
        }

        ScriptPos safe = findSafeSpawnPos(serverWorld, pos);
        entity.refreshPositionAndAngles(safe.x(), safe.y(), safe.z(), 0.0f, 0.0f);
        boolean spawned = serverWorld.spawnEntity(entity);
        if (!spawned) {
            // Keep script flow alive for automation-style scripts.
            // Vanilla/game rules may reject or remove entities; this should not hard-fail the VM thread.
            log("summon skipped/rejected for '" + entityId + "' at " + safe);
            return new ScriptEntity(entityId, entity.getName().getString(), null);
        }
        return new ScriptEntity(entityId, entity.getName().getString(), entity);
    }

    @Override
    public void tp(Object target, double x, double y, double z) {
        Entity entity = resolveEntity(target);
        entity.teleport(world(), x, y, z, Set.<PositionFlag>of(), entity.getYaw(), entity.getPitch(), false);
    }

    @Override
    public void give(Object target, String itemId, int amount) {
        ServerPlayerEntity player = resolvePlayer(target);
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        ItemStack stack = new ItemStack(item, amount);
        boolean inserted = player.getInventory().insertStack(stack);
        if (!inserted || !stack.isEmpty()) {
            player.dropItem(stack, false);
        }
    }

    @Override
    public void kill(Object target) {
        Entity entity = resolveEntity(target);
        entity.kill(world());
    }

    @Override
    public ScriptBlock getBlock(int x, int y, int z) {
        Block block = world().getBlockState(new BlockPos(x, y, z)).getBlock();
        return new ScriptBlock(Registries.BLOCK.getId(block).toString());
    }

    @Override
    public void setBlock(int x, int y, int z, String blockId) {
        Block block = Registries.BLOCK.get(Identifier.of(blockId));
        world().setBlockState(new BlockPos(x, y, z), block.getDefaultState());
    }

    @Override
    public ScriptPlayer getPlayer(String name) {
        ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(name);
        if (player == null) {
            return null;
        }
        return new ScriptPlayer(player.getName().getString(), player);
    }

    @Override
    public String getName(Object target) {
        if (target instanceof ScriptPlayer player) {
            return player.name();
        }
        if (target instanceof ScriptEntity entity) {
            return entity.name();
        }
        if (target instanceof Entity entity) {
            return entity.getName().getString();
        }
        return String.valueOf(target);
    }

    @Override
    public double getHp(Object target) {
        Entity entity = resolveEntity(target);
        if (entity instanceof LivingEntity living) {
            return living.getHealth();
        }
        return 0.0d;
    }

    @Override
    public void setHp(Object target, double hp) {
        Entity entity = resolveEntity(target);
        if (entity instanceof LivingEntity living) {
            float clamped = (float) Math.max(0.0d, Math.min(hp, living.getMaxHealth()));
            living.setHealth(clamped);
            return;
        }
        throw new IllegalStateException("Target has no HP: " + target);
    }

    @Override
    public void addHp(Object target, double delta) {
        Entity entity = resolveEntity(target);
        if (entity instanceof LivingEntity living) {
            double next = living.getHealth() + delta;
            setHp(target, next);
            return;
        }
        throw new IllegalStateException("Target has no HP: " + target);
    }

    @Override
    public int getMana(Object target) {
        ServerPlayerEntity player = resolvePlayer(target);
        Scoreboard scoreboard = source.getServer().getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective("mana");
        if (objective == null) {
            return 0;
        }
        ScoreAccess score = scoreboard.getOrCreateScore(player, objective);
        return score.getScore();
    }

    @Override
    public List<String> scanInventory() {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("inventory scan requires player source");
        }

        List<String> result = new ArrayList<>(36);
        // UI order requested: top-left main inventory (3x9) first, then hotbar.
        for (int slot = 9; slot < 36; slot++) {
            result.add(stackToValue(player.getInventory().getStack(slot)));
        }
        for (int slot = 0; slot < 9; slot++) {
            result.add(stackToValue(player.getInventory().getStack(slot)));
        }
        return result;
    }

    @Override
    public List<String> scanEquipment() {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("equipment scan requires player source");
        }

        List<String> result = new ArrayList<>(4);
        // Top-down equipment slots.
        result.add(stackToValue(player.getEquippedStack(EquipmentSlot.HEAD)));
        result.add(stackToValue(player.getEquippedStack(EquipmentSlot.CHEST)));
        result.add(stackToValue(player.getEquippedStack(EquipmentSlot.LEGS)));
        result.add(stackToValue(player.getEquippedStack(EquipmentSlot.FEET)));
        return result;
    }

    @Override
    public String getOffHand() {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("offhand scan requires player source");
        }
        return stackToValue(player.getOffHandStack());
    }

    @Override
    public void editInventory(int slot, String itemId, int amount, Object nbt) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("editInventory requires player source");
        }
        if (slot < 0 || slot >= 36) {
            throw new IllegalStateException("Invalid inventory slot: " + slot + " (must be 0-35)");
        }
        
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        ItemStack stack = new ItemStack(item, amount);
        
        // Apply NBT if provided (via components or future NBT system)
        if (nbt instanceof java.util.Map<?, ?> nbtMap && !nbtMap.isEmpty()) {
            // NBT application deferred - Minecraft 1.21 uses component system
            // This can be extended when component API is more accessible
        }
        
        // Map UI-based slot to actual inventory slot
        // Slots 0-26 are main inventory (top-left 3x9), 27-35 are hotbar
        int actualSlot = slot < 27 ? slot + 9 : slot - 27;
        player.getInventory().setStack(actualSlot, stack);
    }

    @Override
    public java.util.Map<String, Object> getXp(Object target) {
        ServerPlayerEntity player = resolvePlayer(target);
        java.util.Map<String, Object> xp = new java.util.LinkedHashMap<>();
        xp.put("levels", (long) player.experienceLevel);
        xp.put("progress", (double) player.experienceProgress);
        xp.put("total", (long) player.totalExperience);
        return xp;
    }

    @Override
    public String getGameMode(Object target) {
        ServerPlayerEntity player = resolvePlayer(target);
        GameMode mode = player.interactionManager.getGameMode();
        return mode.asString();
    }

    @Override
    public String getDifficulty() {
        Difficulty difficulty = source.getServer().getSaveProperties().getDifficulty();
        return difficulty.asString();
    }

    @Override
    public void runCommandAsServer(String command) {
        String normalized = command.strip();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalStateException("run() command must not be empty");
        }
        ServerCommandSource serverSource = source.getServer().getCommandSource()
                .withWorld(source.getWorld())
                .withPosition(source.getPosition())
                .withRotation(source.getRotation());
        if (source.getEntity() != null) {
            serverSource = serverSource.withEntity(source.getEntity());
        }
        source.getServer().getCommandManager().parseAndExecute(serverSource, normalized);
    }

    @Override
    public void grantAdvancement(Object target, String advancementId) {
        ServerPlayerEntity player = resolvePlayer(target);
        AdvancementEntry entry = source.getServer().getAdvancementLoader().get(Identifier.of(advancementId));
        if (entry == null) {
            throw new IllegalStateException("Unknown advancement: " + advancementId);
        }
        var progress = player.getAdvancementTracker().getProgress(entry);
        for (String criterion : progress.getUnobtainedCriteria()) {
            player.getAdvancementTracker().grantCriterion(entry, criterion);
        }
    }

    @Override
    public List<String> getAdvancements(Object target) {
        ServerPlayerEntity player = resolvePlayer(target);
        List<String> completed = new ArrayList<>();
        for (AdvancementEntry entry : source.getServer().getAdvancementLoader().getAdvancements()) {
            if (player.getAdvancementTracker().getProgress(entry).isDone()) {
                completed.add(entry.id().toString());
            }
        }
        return completed;
    }

    @Override
    public Map<String, Object> getLook(Object target) {
        Entity entity = resolveEntity(target);
        Map<String, Object> look = new LinkedHashMap<>();
        look.put("yaw", (double) entity.getYaw());
        look.put("pitch", (double) entity.getPitch());
        return look;
    }

    @Override
    public void setLook(Object target, double yaw, double pitch) {
        Entity entity = resolveEntity(target);
        entity.teleport(world(), entity.getX(), entity.getY(), entity.getZ(), Set.<PositionFlag>of(), (float) yaw, (float) pitch, false);
    }

    @Override
    public void clickLeft(Object target) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("clickLeft requires player source");
        }
        if (target != null) {
            Entity entity = resolveEntity(target);
            player.attack(entity);
        } else {
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    @Override
    public void clickRight(Object target) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("clickRight requires player source");
        }
        if (target != null) {
            Entity entity = resolveEntity(target);
            player.interact(entity, Hand.MAIN_HAND);
        } else {
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    @Override
    public void clickMiddle() {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("clickMiddle requires player source");
        }
        // Server-side approximation: reselect current slot to emit an inventory sync tick.
        int current = player.getInventory().getSelectedSlot();
        player.getInventory().setSelectedSlot(current);
        player.playerScreenHandler.sendContentUpdates();
    }

    @Override
    public void scrollHotbar(int delta) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("scrollHotbar requires player source");
        }
        int current = player.getInventory().getSelectedSlot();
        int shifted = Math.floorMod(current + delta, 9);
        player.getInventory().setSelectedSlot(shifted);
    }

    @Override
    public ScriptPlayer requireCurrentPlayer() {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            throw new IllegalStateException("PyScript requires a player command source");
        }
        return new ScriptPlayer(player.getName().getString(), player);
    }

    @Override
    public String currentDimension() {
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            return ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString();
        }
        return world().getRegistryKey().getValue().toString();
    }

    @Override
    public ScriptPos currentPos() {
        ServerPlayerEntity player = source.getPlayer();
        Vec3d pos = player != null ? new Vec3d(player.getX(), player.getY(), player.getZ()) : source.getPosition();
        return new ScriptPos(pos.x, pos.y, pos.z);
    }

    @Override
    public Object currentWorld() {
        return currentDimension();
    }

    @Override
    public Object currentTarget() {
        Entity entity = source.getEntity();
        if (entity == null) {
            return null;
        }
        if (entity instanceof ServerPlayerEntity player) {
            return new ScriptPlayer(player.getName().getString(), player);
        }
        return new ScriptEntity(Registries.ENTITY_TYPE.getId(entity.getType()).toString(), entity.getName().getString(), entity);
    }

    @Override
    public boolean hasPermission(String actionKey) {
        return true;
    }

    private ServerWorld world() {
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            return (ServerWorld) player.getEntityWorld();
        }
        return source.getWorld();
    }

    private Entity resolveEntity(Object raw) {
        if (raw instanceof ScriptPlayer player) {
            return (Entity) player.handle();
        }
        if (raw instanceof ScriptEntity entity) {
            return (Entity) entity.handle();
        }
        if (raw instanceof Entity entity) {
            return entity;
        }
        if (raw instanceof String name) {
            ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(name);
            if (player != null) {
                return player;
            }
        }
        throw new IllegalStateException("Could not resolve entity target from " + raw);
    }

    private ServerPlayerEntity resolvePlayer(Object raw) {
        Entity entity = resolveEntity(raw);
        if (entity instanceof ServerPlayerEntity player) {
            return player;
        }
        throw new IllegalStateException("Target is not a player: " + raw);
    }

    private String stackToValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private ScriptPos findSafeSpawnPos(ServerWorld serverWorld, ScriptPos desired) {
        BlockPos base = BlockPos.ofFloored(desired.x(), desired.y(), desired.z());
        for (int dy = 0; dy <= 8; dy++) {
            BlockPos feet = base.up(dy);
            BlockPos head = feet.up();
            if (serverWorld.getBlockState(feet).isAir() && serverWorld.getBlockState(head).isAir()) {
                return new ScriptPos(feet.getX() + 0.5d, feet.getY(), feet.getZ() + 0.5d);
            }
        }
        return desired;
    }

    private NbtCompound mapToNbt(java.util.Map<?, ?> map) {
        NbtCompound compound = new NbtCompound();
        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
            String key = entry.getKey().toString();
            Object value = entry.getValue();
            
            if (value instanceof String str) {
                compound.putString(key, str);
            } else if (value instanceof Number num) {
                if (value instanceof Long l) {
                    compound.putLong(key, l);
                } else if (value instanceof Double d) {
                    compound.putDouble(key, d);
                } else if (value instanceof Integer i) {
                    compound.putInt(key, i);
                } else {
                    compound.putDouble(key, num.doubleValue());
                }
            } else if (value instanceof Boolean bool) {
                compound.putBoolean(key, bool);
            } else if (value instanceof java.util.List<?> list) {
                // Simple list handling - convert to byte array for simplicity
                // In real scenarios, might need more sophisticated list handling
            } else if (value instanceof java.util.Map<?, ?> nestedMap) {
                compound.put(key, mapToNbt(nestedMap));
            }
        }
        return compound;
    }
}
