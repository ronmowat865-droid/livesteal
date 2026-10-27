package com.livesteal.mixin;

import com.livesteal.LivestealMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** If a unique item was already crafted in this world, the crafting table shows no result. */
@Mixin(CraftingScreenHandler.class)
public abstract class CraftingScreenHandlerMixin {
    @Inject(method = "updateResult", at = @At("TAIL"))
    private static void ls$blockDuplicates(ScreenHandler handler, ServerWorld world, PlayerEntity player,
                                           RecipeInputInventory craftingInventory,
                                           CraftingResultInventory resultInventory,
                                           RecipeEntry<CraftingRecipe> recipe, CallbackInfo ci) {
        ItemStack result = resultInventory.getStack(0);
        if (!result.isEmpty() && LivestealMod.isAlreadyCrafted(result) && player instanceof ServerPlayerEntity sp) {
            resultInventory.setStack(0, ItemStack.EMPTY);
            handler.setPreviousTrackedSlot(0, ItemStack.EMPTY);
            sp.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(
                    handler.syncId, handler.nextRevision(), 0, ItemStack.EMPTY));
            sp.sendMessage(net.minecraft.text.Text.literal("That item has already been crafted in this world."), true);
        }
    }
}
