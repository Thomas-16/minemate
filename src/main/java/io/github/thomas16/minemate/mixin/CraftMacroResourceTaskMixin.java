package io.github.thomas16.minemate.mixin;

import com.player2.playerengine.tasks.crafting.CraftMacroResourceTask;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Keeps PlayerEngine's craft macro alive for its full bounded material search. */
@Mixin(value = CraftMacroResourceTask.class, remap = false)
public abstract class CraftMacroResourceTaskMixin {
    private static final int EXTENDED_COLLECTION_LIMIT_TICKS = 2400;

    @ModifyConstant(
            method = "tickCollectMissingMaterials",
            constant = @Constant(intValue = 300),
            require = 1)
    private int minemate$extendMissingSourceSearch(int original) {
        return EXTENDED_COLLECTION_LIMIT_TICKS;
    }

    @ModifyConstant(
            method = "tickCollectMissingMaterials",
            constant = @Constant(intValue = 1200),
            require = 1)
    private int minemate$extendNoGainSearch(int original) {
        return EXTENDED_COLLECTION_LIMIT_TICKS;
    }
}
