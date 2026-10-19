# FastPlace Pro (Minecraft 1.16.5 Fabric)

A client-side Fast Block Placement mod for **Minecraft 1.16.5 (Java Edition - Fabric)** designed for high-speed building, terraforming, and speed-bridging without accidental misplacements.

## Features

1. **Zero-Tick Block Placement Cooldown (`MinecraftClientMixin`)**:
   - Overrides Vanilla's 4-tick (`200ms`, 5 blocks/sec) `itemUseCooldown` in `MinecraftClient.doItemUse()` and `MinecraftClient.tick()`.
   - Cycles between **0 ticks** (20 blocks/sec), **1 tick** (10 blocks/sec), and **2 ticks** (6.7 blocks/sec).
2. **Smart Block-Only Filtering**:
   - Only accelerates right-click cooldown when holding a `BlockItem` in your main hand or off-hand.
   - Leaves bows, shields, food, potions, ender pearls, firework rockets, and buckets at normal speed so you never double-consume items.
3. **Interactive Block Protection**:
   - Automatically keeps normal cooldown when right-clicking chests, doors, trapdoors, fence gates, levers, buttons, repeaters, comparators, or crafting tables while not sneaking.
4. **Planar & Directional Placement Locks**:
   - **Free (Unlocked)**: Places blocks anywhere your crosshair points at full speed.
   - **Floor / Bridge (Y-Plane Lock)**: Locks block placement to the initial horizontal `Y` coordinate while Right-Click is held, preventing blocks from placing on top of your bridge or blocking your feet.
   - **Wall / Surface (Face-Plane Lock)**: Locks placement to the initial clicked block face and 2D surface plane for rapid wall construction.
   - **Straight Line / Pillar (Axis Lock)**: Locks placement along a 1D axis line from the first placed block.

## Default Keybinds (Configurable in Controls -> FastPlace Pro)

- **`V`**: Toggle FastPlace Pro ON / OFF
- **`B`**: Cycle Placement Lock Mode (`Free` -> `Floor / Bridge Y-Plane` -> `Wall / Surface Plane` -> `Straight Line`)
- **`N`**: Cycle Placement Delay (`0 ticks` -> `1 tick` -> `2 ticks`)

## Building the Mod JAR

1. Extract this project folder and open a terminal in the project root.
2. Run Gradle with JDK 8 or JDK 16/17:
   ```bash
   ./gradlew build
   ```
   *(Or `gradle build` if using a local Gradle installation)*
3. Copy `build/libs/fastplace-pro-1.0.0.jar` into your `.minecraft/mods/` folder alongside `fabric-api-0.42.0+1.16.jar`.
