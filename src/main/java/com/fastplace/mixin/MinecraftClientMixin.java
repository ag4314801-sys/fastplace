package com.fastplace.mixin;

import com.fastplace.FastPlaceClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.GameOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into MinecraftClient (net.minecraft.class_310 in Intermediary mappings)
 * to control itemUseCooldown (field_1752) and filter doItemUse (method_1583).
 */
@Environment(EnvType.CLIENT)
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Shadow
    private int itemUseCooldown;

    @Shadow
    @Final
    public GameOptions options;

    /**
     * At the start of each client tick:
     * 1. Reset the directional/planar placement lock as soon as the player releases Right-Click.
     * 2. Clamp itemUseCooldown down to the configured FastPlace delay when holding a valid block.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void fastplace$onTickHead(CallbackInfo ci) {
        if (this.options != null && !this.options.keyUse.isPressed()) {
            FastPlaceClient.resetLockState();
        }

        MinecraftClient client = (MinecraftClient) (Object) this;
        if (FastPlaceClient.shouldAccelerate(client)) {
            int target = FastPlaceClient.getTargetCooldown();
            if (this.itemUseCooldown > target) {
                this.itemUseCooldown = target;
            }
        }
    }

    /**
     * Before doItemUse executes:
     * If a PlacementLockMode (Floor/Bridge Y-Plane, Wall Face-Plane, or Axis Line) is active,
     * cancel placement on ticks where the crosshair slips outside the locked plane or line.
     */
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void fastplace$onDoItemUseHead(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (FastPlaceClient.shouldCancelLockedPlacement(client)) {
            this.itemUseCooldown = 0;
            ci.cancel();
        }
    }

    /**
     * Immediately after doItemUse sets itemUseCooldown = 4:
     * Override itemUseCooldown with the configured target delay (default 0 ticks = 20 blocks/sec)
     * so the next tick's handleInputEvents() can immediately place the next block without lag.
     */
    @Inject(method = "doItemUse", at = @At("TAIL"))
    private void fastplace$onDoItemUseTail(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (FastPlaceClient.shouldAccelerate(client)) {
            int target = FastPlaceClient.getTargetCooldown();
            if (this.itemUseCooldown > target) {
                this.itemUseCooldown = target;
            }
        }
    }
}
