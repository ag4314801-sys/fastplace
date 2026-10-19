package com.fastplace;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractButtonBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * FastPlace Pro - Client-side Fast Block Placement Mod for Minecraft 1.16.5 (Fabric).
 *
 * Key Features:
 * 1. Overrides MinecraftClient.itemUseCooldown (default 4 ticks -> 0, 1, or 2 ticks).
 * 2. Smart Block-Only Guard: Only accelerates cooldown when holding a BlockItem, preventing
 *    accidental double-consumption of food, potions, ender pearls, bows, or buckets.
 * 3. Interactive Block Protection: Avoids spamming doors, trapdoors, levers, repeaters,
 *    or containers when right-clicking without sneaking.
 * 4. Planar & Directional Placement Lock: Prevents accidental vertical/sideways misplacements
 *    when building floors, walls, pillars, or speed-bridging at 20 blocks/sec.
 */
@Environment(EnvType.CLIENT)
public class FastPlaceClient implements ClientModInitializer {

    public enum PlacementLockMode {
        FREE("Free (Unlocked)", Formatting.GREEN),
        HORIZONTAL_PLANE("Floor / Bridge (Y-Plane Lock)", Formatting.AQUA),
        SURFACE_PLANE("Wall / Surface (Face-Plane Lock)", Formatting.GOLD),
        AXIS_LINE("Straight Line / Pillar (Axis Lock)", Formatting.LIGHT_PURPLE);

        private final String displayName;
        private final Formatting color;

        PlacementLockMode(String displayName, Formatting color) {
            this.displayName = displayName;
            this.color = color;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Formatting getColor() {
            return color;
        }

        public PlacementLockMode next() {
            PlacementLockMode[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }

    // Configurable settings (persisted in config/fastplace.properties)
    private static boolean enabled = true;
    private static int targetCooldown = 0; // 0 = 20 bps (every tick), 1 = 10 bps, 2 = 6.7 bps
    private static boolean blocksOnly = true;
    private static boolean protectInteractiveBlocks = true;
    private static PlacementLockMode lockMode = PlacementLockMode.FREE;

    // Runtime state for directional/planar lock while Right-Click (keyUse) is held
    private static BlockPos lockedOrigin = null;
    private static Direction lockedFace = null;

    // Keybindings
    private static KeyBinding toggleKey;
    private static KeyBinding lockModeKey;
    private static KeyBinding speedCycleKey;

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("fastplace.properties");

    @Override
    public void onInitializeClient() {
        loadConfig();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fastplace.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.fastplace.general"
        ));

        lockModeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fastplace.lock_mode",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.fastplace.general"
        ));

        speedCycleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fastplace.speed",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                "category.fastplace.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                resetLockState();
                saveConfig();
                sendHudMessage(client,
                        Formatting.GRAY + "[FastPlace] " + Formatting.WHITE + "Mode: "
                                + (enabled ? Formatting.GREEN + "ENABLED" : Formatting.RED + "DISABLED")
                                + Formatting.GRAY + " (" + getSpeedDescription(targetCooldown) + ")");
            }

            while (lockModeKey.wasPressed()) {
                lockMode = lockMode.next();
                resetLockState();
                saveConfig();
                sendHudMessage(client,
                        Formatting.GRAY + "[FastPlace] " + Formatting.WHITE + "Placement Lock: "
                                + lockMode.getColor() + lockMode.getDisplayName());
            }

            while (speedCycleKey.wasPressed()) {
                targetCooldown = (targetCooldown + 1) % 3; // Cycles 0 -> 1 -> 2 -> 0
                saveConfig();
                sendHudMessage(client,
                        Formatting.GRAY + "[FastPlace] " + Formatting.WHITE + "Delay: "
                                + Formatting.YELLOW + getSpeedDescription(targetCooldown));
            }
        });
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getTargetCooldown() {
        return targetCooldown;
    }

    public static PlacementLockMode getLockMode() {
        return lockMode;
    }

    public static void resetLockState() {
        lockedOrigin = null;
        lockedFace = null;
    }

    /**
     * Determines whether the cooldown should be lowered for the current client state.
     */
    public static boolean shouldAccelerate(MinecraftClient client) {
        if (!enabled || client == null || client.player == null || client.world == null) {
            return false;
        }
        if (client.player.isUsingItem()) {
            return false;
        }
        if (blocksOnly && !isHoldingPlaceableBlock(client.player)) {
            return false;
        }
        if (protectInteractiveBlocks && isTargetingInteractiveBlock(client)) {
            return false;
        }
        return true;
    }

    /**
     * Evaluates whether the current right-click block placement should be cancelled
     * to enforce the active PlacementLockMode (Floor/Bridge Y-Plane, Wall Face-Plane, or Axis Line).
     */
    public static boolean shouldCancelLockedPlacement(MinecraftClient client) {
        if (!enabled || lockMode == PlacementLockMode.FREE) {
            return false;
        }
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        if (!isHoldingPlaceableBlock(client.player)) {
            return false;
        }
        if (client.crosshairTarget == null || client.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        BlockHitResult hit = (BlockHitResult) client.crosshairTarget;
        BlockPos clickedPos = hit.getBlockPos();
        Direction side = hit.getSide();

        // Compute the destination BlockPos where the new block will actually be placed
        BlockState clickedState = client.world.getBlockState(clickedPos);
        BlockPos targetPlacePos = clickedState.getMaterial().isReplaceable()
                ? clickedPos
                : clickedPos.offset(side);

        // First block placed during this right-click hold establishes the lock anchor
        if (lockedOrigin == null || lockedFace == null) {
            lockedOrigin = targetPlacePos.toImmutable();
            lockedFace = side;
            return false;
        }

        switch (lockMode) {
            case HORIZONTAL_PLANE:
                // Only allow placing blocks on the exact same horizontal Y level (perfect for bridging & floors)
                if (targetPlacePos.getY() != lockedOrigin.getY()) {
                    return true;
                }
                // Prevent placing a block inside the player's own head space while sprinting/bridging
                if (targetPlacePos.equals(client.player.getBlockPos().up())) {
                    return true;
                }
                return false;

            case SURFACE_PLANE:
                // Must click the same block face direction and stay in the same 2D plane perpendicular to that axis
                if (side != lockedFace) {
                    return true;
                }
                return getAxisCoordinate(targetPlacePos, lockedFace.getAxis())
                        != getAxisCoordinate(lockedOrigin, lockedFace.getAxis());

            case AXIS_LINE:
                // Must stay collinear with lockedOrigin along the axis of the initially clicked face
                return !isCollinearAlongAxis(targetPlacePos, lockedOrigin, lockedFace.getAxis());

            default:
                return false;
        }
    }

    private static boolean isHoldingPlaceableBlock(ClientPlayerEntity player) {
        ItemStack mainHand = player.getStackInHand(Hand.MAIN_HAND);
        if (!mainHand.isEmpty()) {
            return mainHand.getItem() instanceof BlockItem;
        }
        ItemStack offHand = player.getStackInHand(Hand.OFF_HAND);
        return !offHand.isEmpty() && (offHand.getItem() instanceof BlockItem);
    }

    private static boolean isTargetingInteractiveBlock(MinecraftClient client) {
        if (client.player == null || client.world == null || client.player.isSneaking()) {
            return false;
        }
        if (client.crosshairTarget == null || client.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockHitResult hit = (BlockHitResult) client.crosshairTarget;
        BlockState state = client.world.getBlockState(hit.getBlockPos());
        Block block = state.getBlock();

        return block instanceof BlockWithEntity
                || block instanceof CraftingTableBlock
                || block instanceof AnvilBlock
                || block instanceof DoorBlock
                || block instanceof TrapdoorBlock
                || block instanceof FenceGateBlock
                || block instanceof LeverBlock
                || block instanceof AbstractButtonBlock
                || block instanceof RepeaterBlock
                || block instanceof ComparatorBlock
                || block instanceof NoteBlock;
    }

    private static int getAxisCoordinate(BlockPos pos, Direction.Axis axis) {
        switch (axis) {
            case X:
                return pos.getX();
            case Y:
                return pos.getY();
            case Z:
            default:
                return pos.getZ();
        }
    }

    private static boolean isCollinearAlongAxis(BlockPos pos, BlockPos origin, Direction.Axis axis) {
        switch (axis) {
            case X:
                return pos.getY() == origin.getY() && pos.getZ() == origin.getZ();
            case Y:
                return pos.getX() == origin.getX() && pos.getZ() == origin.getZ();
            case Z:
            default:
                return pos.getX() == origin.getX() && pos.getY() == origin.getY();
        }
    }

    private static String getSpeedDescription(int cooldown) {
        switch (cooldown) {
            case 0:
                return "0 ticks (20 blocks/sec - Max)";
            case 1:
                return "1 tick (10 blocks/sec - Fast)";
            case 2:
                return "2 ticks (6.7 blocks/sec - Controlled)";
            default:
                return cooldown + " ticks";
        }
    }

    private static void sendHudMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(new LiteralText(message), true);
        }
    }

    private static void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            saveConfig();
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            props.load(in);
            enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
            targetCooldown = Math.max(0, Math.min(4, Integer.parseInt(props.getProperty("targetCooldown", "0"))));
            blocksOnly = Boolean.parseBoolean(props.getProperty("blocksOnly", "true"));
            protectInteractiveBlocks = Boolean.parseBoolean(props.getProperty("protectInteractiveBlocks", "true"));
            String modeName = props.getProperty("lockMode", PlacementLockMode.FREE.name());
            try {
                lockMode = PlacementLockMode.valueOf(modeName);
            } catch (IllegalArgumentException ignored) {
                lockMode = PlacementLockMode.FREE;
            }
        } catch (IOException | NumberFormatException ignored) {
        }
    }

    private static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("enabled", String.valueOf(enabled));
        props.setProperty("targetCooldown", String.valueOf(targetCooldown));
        props.setProperty("blocksOnly", String.valueOf(blocksOnly));
        props.setProperty("protectInteractiveBlocks", String.valueOf(protectInteractiveBlocks));
        props.setProperty("lockMode", lockMode.name());
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                props.store(out, "FastPlace Pro 1.16.5 Configuration");
            }
        } catch (IOException ignored) {
        }
    }
}
