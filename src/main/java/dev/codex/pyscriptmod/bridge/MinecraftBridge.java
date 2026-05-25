package dev.codex.pyscriptmod.bridge;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

public interface MinecraftBridge {
    record ScriptPos(double x, double y, double z) {
        public Map<String, Object> asMap() {
            return Map.of("x", x, "y", y, "z", z);
        }
    }

    record ScriptBlock(String id) {
    }

    record ScriptEntity(String id, String name, Object handle) {
    }

    record ScriptPlayer(String name, Object handle) {
    }

    void say(String message);

    void log(String message);

    ScriptEntity summon(String entityId, ScriptPos pos);

    void tp(Object target, double x, double y, double z);

    void give(Object target, String itemId, int amount);

    void kill(Object target);

    ScriptBlock getBlock(int x, int y, int z);

    void setBlock(int x, int y, int z, String blockId);

    ScriptPlayer getPlayer(String name);

    String getName(Object target);

    double getHp(Object target);

    void setHp(Object target, double hp);

    void addHp(Object target, double delta);

    int getMana(Object target);

    List<String> scanInventory();

    List<String> scanEquipment();

    String getOffHand();

    void editInventory(int slot, String itemId, int amount, Object nbt);

    Map<String, Object> getXp(Object target);

    String getGameMode(Object target);

    String getDifficulty();

    void runCommandAsServer(String command);

    void grantAdvancement(Object target, String advancementId);

    List<String> getAdvancements(Object target);

    Map<String, Object> getLook(Object target);

    void setLook(Object target, double yaw, double pitch);

    void clickLeft(Object target);

    void clickRight(Object target);

    void clickMiddle();

    void scrollHotbar(int delta);

    ScriptPlayer requireCurrentPlayer();

    String currentDimension();

    ScriptPos currentPos();

    Object currentWorld();

    Object currentTarget();

    boolean hasPermission(String actionKey);

    default Map<String, Object> initialBindings() {
        ScriptPlayer player = requireCurrentPlayer();
        ScriptPos pos = currentPos();
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("player", player);
        bindings.put("world", currentWorld());
        bindings.put("dimension", currentDimension());
        bindings.put("pos", pos.asMap());
        bindings.put("target", currentTarget());
        return bindings;
    }
}
