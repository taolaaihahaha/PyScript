import dev.codex.pyscriptmod.bridge.MinecraftBridge;
import dev.codex.pyscriptmod.script.io.ScriptModuleLoader;
import dev.codex.pyscriptmod.script.runtime.ScriptRuntime;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SmokeMain {
    public static void main(String[] args) {
        ScriptRuntime runtime = new ScriptRuntime(new ScriptModuleLoader(Path.of("example-scripts")), key -> false);
        FakeBridge bridge = new FakeBridge();
        runtime.runModule("demo", bridge, Map.of("nickname", "smoke"));
        for (int i = 0; i < 25; i++) {
            runtime.tick();
        }
    }

    private static final class FakeBridge implements MinecraftBridge {
        @Override
        public void say(String message) {
            System.out.println("[say] " + message);
        }

        @Override
        public void log(String message) {
            System.out.println("[log] " + message);
        }

        @Override
        public ScriptEntity summon(String entityId, ScriptPos pos) {
            System.out.println("[summon] " + entityId + " at " + pos);
            return new ScriptEntity(entityId, entityId, entityId);
        }

        @Override
        public void tp(Object target, double x, double y, double z) {
            System.out.println("[tp] " + target + " -> " + x + "," + y + "," + z);
        }

        @Override
        public void give(Object target, String itemId, int amount) {
            System.out.println("[give] " + target + " " + itemId + " x" + amount);
        }

        @Override
        public void kill(Object target) {
            System.out.println("[kill] " + target);
        }

        @Override
        public ScriptBlock getBlock(int x, int y, int z) {
            return new ScriptBlock("minecraft:stone");
        }

        @Override
        public void setBlock(int x, int y, int z, String blockId) {
            System.out.println("[set_block] " + x + "," + y + "," + z + " -> " + blockId);
        }

        @Override
        public ScriptPlayer getPlayer(String name) {
            return new ScriptPlayer(name, name);
        }

        @Override
        public String getName(Object target) {
            return String.valueOf(target instanceof ScriptPlayer player ? player.name() : target);
        }

        @Override
        public double getHp(Object target) {
            return 18.0d;
        }

        @Override
        public void setHp(Object target, double hp) {
            System.out.println("[set_hp] " + target + " => " + hp);
        }

        @Override
        public void addHp(Object target, double delta) {
            System.out.println("[add_hp] " + target + " += " + delta);
        }

        @Override
        public int getMana(Object target) {
            return 7;
        }

        @Override
        public List<String> scanInventory() {
            return List.of("empty");
        }

        @Override
        public List<String> scanEquipment() {
            return List.of("empty", "empty", "empty", "empty");
        }

        @Override
        public String getOffHand() {
            return "empty";
        }

        @Override
        public Map<String, Object> getXp(Object target) {
            Map<String, Object> xp = new LinkedHashMap<>();
            xp.put("levels", 3L);
            xp.put("progress", 0.5d);
            xp.put("total", 37L);
            return xp;
        }

        @Override
        public String getGameMode(Object target) {
            return "survival";
        }

        @Override
        public String getDifficulty() {
            return "normal";
        }

        @Override
        public void runCommandAsServer(String command) {
            System.out.println("[run] " + command);
        }

        @Override
        public void grantAdvancement(Object target, String advancementId) {
            System.out.println("[grant_adv] " + target + " " + advancementId);
        }

        @Override
        public List<String> getAdvancements(Object target) {
            return List.of("minecraft:story/mine_stone");
        }

        @Override
        public Map<String, Object> getLook(Object target) {
            Map<String, Object> look = new LinkedHashMap<>();
            look.put("yaw", 0.0d);
            look.put("pitch", 0.0d);
            return look;
        }

        @Override
        public void setLook(Object target, double yaw, double pitch) {
            System.out.println("[set_look] " + target + " yaw=" + yaw + " pitch=" + pitch);
        }

        @Override
        public void clickLeft(Object target) {
            System.out.println("[click_lmb] " + target);
        }

        @Override
        public void clickRight(Object target) {
            System.out.println("[click_rmb] " + target);
        }

        @Override
        public void clickMiddle() {
            System.out.println("[click_mmb]");
        }

        @Override
        public void scrollHotbar(int delta) {
            System.out.println("[scroll] " + delta);
        }

        @Override
        public ScriptPlayer requireCurrentPlayer() {
            return new ScriptPlayer("SmokePlayer", "SmokePlayer");
        }

        @Override
        public String currentDimension() {
            return "minecraft:overworld";
        }

        @Override
        public ScriptPos currentPos() {
            return new ScriptPos(0, 64, 0);
        }

        @Override
        public Object currentWorld() {
            return "minecraft:overworld";
        }

        @Override
        public Object currentTarget() {
            return null;
        }

        @Override
        public boolean hasPermission(String actionKey) {
            return true;
        }
    }
}
