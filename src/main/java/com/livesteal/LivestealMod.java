package com.livesteal;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class LivestealMod implements ModInitializer {
    public static final String MOD_ID = "livesteal";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** custom_data key that every special item carries (set by the recipe JSONs). */
    public static final String KEY = "livesteal_id";

    // ---- tuning (ticks = seconds * 20) ----
    static final int MAX_EXTRA_HEARTS   = 5;
    static final int POSEIDON_CD        = 60 * 20;
    static final double POSEIDON_LAUNCH = 2.2;      // upward velocity
    static final int SWAP_CD            = 100 * 20;
    static final double SWAP_RANGE      = 64;
    static final int GREATSWORD_CD      = 70 * 20;
    static final int LUNGE_CD           = 15 * 20;  // cooldown on the spear's lunge
    static final int LUNGE_LEVEL        = 13;

    /** Items that can only ever be crafted once per world. (The heart is NOT in here.) */
    public static final Set<String> UNIQUE = Set.of(
            "poseidon_trident", "flaming_crossbow", "ls_netherite_sword",
            "ls_spear", "ls_mace", "greatsword", "swap_amethyst");

    private static final Identifier HEART_MOD = Identifier.of(MOD_ID, "extra_hearts");

    public static MinecraftServer SERVER;
    public static Data DATA = new Data();
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new HashMap<>();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { SERVER = s; DATA = Data.load(s); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> DATA.save());

        // ---------- join: rank + hearts ----------
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            String u = p.getUuidAsString();
            if (!DATA.ranks.containsKey(u)) {
                DATA.ranks.put(u, DATA.nextRank++);   // first joiner = rank 1 = highest
                DATA.save();
            }
            applyTeam(p);
            applyHearts(p);
            p.sendMessage(Text.literal("Your rank is #" + DATA.ranks.get(u) + " (#1 is the highest)."), false);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> { applyHearts(newP); applyTeam(newP); });

        // ---------- kills: head drop + rank swap ----------
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity victim)) return;
            if (!(source.getAttacker() instanceof ServerPlayerEntity killer) || killer == victim) return;

            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(victim.getGameProfile()));
            victim.dropStack((ServerWorld) victim.getEntityWorld(), head);

            int rv = DATA.ranks.getOrDefault(victim.getUuidAsString(), Integer.MAX_VALUE);
            int rk = DATA.ranks.getOrDefault(killer.getUuidAsString(), Integer.MAX_VALUE);
            if (rv < rk) { // victim had the higher rank (lower number) -> swap
                DATA.ranks.put(victim.getUuidAsString(), rk);
                DATA.ranks.put(killer.getUuidAsString(), rv);
                DATA.save();
                applyTeam(victim);
                applyTeam(killer);
                victim.sendMessage(Text.literal("You lost your head! Your rank is now #" + rk + "."), false);
                killer.sendMessage(Text.literal("You took " + victim.getName().getString() + "'s rank! You are now #" + rv + "."), false);
            }
        });

        // ---------- right-click abilities ----------
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            ItemStack stack = sp.getStackInHand(hand);
            switch (idOf(stack)) {
                case "heart" -> { return useHeart(sp, stack); }
                case "poseidon_trident" -> { return usePoseidon(sp, stack); }
                case "swap_amethyst" -> { return useSwap(sp, stack); }
                default -> { return ActionResult.PASS; }
            }
        });

        // ---------- greatsword: 70s cooldown after a swing at something ----------
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            ItemStack stack = sp.getStackInHand(hand);
            if (!idOf(stack).equals("greatsword")) return ActionResult.PASS;
            long rem = remaining(sp, "greatsword");
            if (rem > 0) {
                sp.sendMessage(Text.literal("Greatsword recharging: " + (rem / 20 + 1) + "s"), true);
                return ActionResult.FAIL;
            }
            startCooldown(sp, stack, "greatsword", GREATSWORD_CD);
            return ActionResult.PASS;
        });

        // ---------- flaming crossbow: arrows are on fire ----------
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PersistentProjectileEntity arrow) {
                ItemStack weapon = arrow.getWeaponStack();
                if (weapon != null && idOf(weapon).equals("flaming_crossbow")) {
                    arrow.setOnFireFor(100);
                }
            }
        });

        // ---------- spear: lunge cooldown (lunge enchant is removed while recharging) ----------
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                ItemStack main = p.getMainHandStack();
                if (!idOf(main).equals("ls_spear")) continue;
                boolean hasLunge = EnchantmentHelper.getLevel(lunge(), main) > 0;
                long rem = remaining(p, "lunge");
                if (rem > 0) {
                    if (hasLunge) setLunge(main, false);
                    if (rem % 20 == 0) p.sendMessage(Text.literal("Lunge ready in " + (rem / 20) + "s"), true);
                } else if (!hasLunge) {
                    setLunge(main, true);
                    p.sendMessage(Text.literal("Lunge ready!"), true);
                } else if (p.handSwinging && p.handSwingTicks == 0) {
                    // a swing/jab just happened with lunge available -> put it on cooldown
                    COOLDOWNS.computeIfAbsent(p.getUuid(), k -> new HashMap<>())
                            .put("lunge", (long) server.getTicks() + LUNGE_CD);
                    setLunge(main, false);
                }
            }
        });
    }

    // =====================================================================
    // abilities
    // =====================================================================

    private static ActionResult useHeart(ServerPlayerEntity p, ItemStack stack) {
        int have = DATA.hearts.getOrDefault(p.getUuidAsString(), 0);
        if (have >= MAX_EXTRA_HEARTS) {
            p.sendMessage(Text.literal("You already have the maximum of " + MAX_EXTRA_HEARTS + " extra hearts."), true);
            return ActionResult.FAIL;
        }
        DATA.hearts.put(p.getUuidAsString(), have + 1);
        DATA.save();
        applyHearts(p);
        p.setHealth(p.getHealth() + 2f);
        stack.decrement(1);
        p.sendMessage(Text.literal("+1 heart (" + (have + 1) + "/" + MAX_EXTRA_HEARTS + ")"), true);
        return ActionResult.SUCCESS;
    }

    private static ActionResult usePoseidon(ServerPlayerEntity p, ItemStack stack) {
        long rem = remaining(p, "poseidon");
        if (rem > 0) {
            p.sendMessage(Text.literal("Poseidon's Trident recharging: " + (rem / 20 + 1) + "s"), true);
            return ActionResult.FAIL;
        }
        Vec3d v = p.getVelocity();
        p.setVelocity(v.x, POSEIDON_LAUNCH, v.z);
        p.velocityModified = true;
        p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
        // so the launch doesn't end in fall damage
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 15, 0, false, false));
        startCooldown(p, stack, "poseidon", POSEIDON_CD);
        return ActionResult.SUCCESS;
    }

    private static ActionResult useSwap(ServerPlayerEntity p, ItemStack stack) {
        long rem = remaining(p, "swap");
        if (rem > 0) {
            p.sendMessage(Text.literal("Amethyst recharging: " + (rem / 20 + 1) + "s"), true);
            return ActionResult.FAIL;
        }
        ServerPlayerEntity t = findTarget(p, SWAP_RANGE);
        if (t == null) {
            p.sendMessage(Text.literal("No player under your crosshair."), true);
            return ActionResult.FAIL;
        }
        ServerWorld w = (ServerWorld) p.getEntityWorld();
        double ax = p.getX(), ay = p.getY(), az = p.getZ();
        double bx = t.getX(), by = t.getY(), bz = t.getZ();
        p.teleport(w, bx, by, bz, Set.of(), p.getYaw(), p.getPitch(), true);
        t.teleport(w, ax, ay, az, Set.of(), t.getYaw(), t.getPitch(), true);
        t.sendMessage(Text.literal(p.getName().getString() + " swapped places with you!"), true);
        startCooldown(p, stack, "swap", SWAP_CD);
        return ActionResult.SUCCESS;
    }

    private static ServerPlayerEntity findTarget(ServerPlayerEntity p, double range) {
        ServerWorld w = (ServerWorld) p.getEntityWorld();
        Vec3d eye = p.getEyePos();
        Vec3d end = eye.add(p.getRotationVec(1.0f).multiply(range));
        ServerPlayerEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayerEntity o : w.getPlayers()) {
            if (o == p || o.isSpectator()) continue;
            Box box = o.getBoundingBox().expand(0.3);
            Optional<Vec3d> hit = box.raycast(eye, end);
            if (hit.isEmpty()) continue;
            BlockHitResult block = w.raycast(new RaycastContext(eye, hit.get(),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
            if (block.getType() != HitResult.Type.MISS) continue; // wall in the way
            double d = eye.squaredDistanceTo(hit.get());
            if (d < bestD) { bestD = d; best = o; }
        }
        return best;
    }

    // =====================================================================
    // helpers
    // =====================================================================

    public static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        NbtComponent c = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return "";
        return c.copyNbt().getString(KEY).orElse("");
    }

    /** Used by the crafting-table mixins. */
    public static boolean isAlreadyCrafted(ItemStack stack) {
        String id = idOf(stack);
        return UNIQUE.contains(id) && DATA.crafted.contains(id);
    }

    public static void onResultTaken(ItemStack stack) {
        String id = idOf(stack);
        if (!UNIQUE.contains(id)) return;
        if (DATA.crafted.contains(id)) {
            stack.setCount(0); // someone else already took it a moment ago: void this copy
            return;
        }
        DATA.crafted.add(id);
        DATA.save();
        LOGGER.info("Unique item '{}' has been crafted and can no longer be crafted in this world.", id);
        if (SERVER != null) {
            SERVER.getPlayerManager().broadcast(Text.literal("The unique item \"" + id + "\" has been crafted!")
                    .formatted(Formatting.GOLD), false);
        }
    }

    private static long remaining(ServerPlayerEntity p, String key) {
        Long end = COOLDOWNS.getOrDefault(p.getUuid(), Map.of()).get(key);
        if (end == null) return 0;
        return Math.max(0, end - SERVER.getTicks());
    }

    private static void startCooldown(ServerPlayerEntity p, ItemStack stack, String key, int ticks) {
        COOLDOWNS.computeIfAbsent(p.getUuid(), k -> new HashMap<>()).put(key, (long) SERVER.getTicks() + ticks);
        p.getItemCooldownManager().set(stack, ticks); // visual overlay (uses the recipe's use_cooldown group)
    }

    private static void applyHearts(ServerPlayerEntity p) {
        EntityAttributeInstance attr = p.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (attr == null) return;
        attr.removeModifier(HEART_MOD);
        int n = DATA.hearts.getOrDefault(p.getUuidAsString(), 0);
        if (n > 0) {
            attr.addPersistentModifier(new EntityAttributeModifier(HEART_MOD, n * 2.0,
                    EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** Rank shows as a [#N] prefix in chat, tab list and above the head (via a scoreboard team). */
    private static void applyTeam(ServerPlayerEntity p) {
        int rank = DATA.ranks.getOrDefault(p.getUuidAsString(), 0);
        if (rank == 0) return;
        Scoreboard sb = SERVER.getScoreboard();
        String name = "ls_rank_" + rank;
        Team t = sb.getTeam(name);
        if (t == null) t = sb.addTeam(name);
        t.setPrefix(Text.literal("[#" + rank + "] ").formatted(Formatting.GOLD));
        sb.addScoreHolderToTeam(p.getNameForScoreboard(), t);
    }

    private static RegistryEntry<Enchantment> lunge() {
        return SERVER.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.LUNGE);
    }

    private static void setLunge(ItemStack stack, boolean on) {
        RegistryEntry<Enchantment> l = lunge();
        EnchantmentHelper.apply(stack, b -> {
            if (on) b.set(l, LUNGE_LEVEL);
            else b.remove(e -> e.matchesKey(Enchantments.LUNGE));
        });
    }
}
