package com.livesteal.mixin;

import com.livesteal.LivestealMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks a unique item as "crafted" the moment someone takes it out of the result slot. */
@Mixin(CraftingResultSlot.class)
public class CraftingResultSlotMixin {
    @Inject(method = "onTakeItem", at = @At("HEAD"))
    private void ls$markCrafted(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity) {
            LivestealMod.onResultTaken(stack);
        }
    }
}
