package dev.cubicchunks.mixin;

import dev.cubicchunks.core.LightProbe;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reproduction aid for a fault that has followed this mod since early on:
 * {@code NullPointerException: Cannot invoke DataLayer.get(int,int,int) because "layer" is null}
 * raised out of {@link LayerLightSectionStorage#getStoredLevel}. The exception names no position,
 * so every attempt to fix it has aimed at the calls this mod makes instead of asking which section
 * was missing. Guessing produced a guard in 0.2.6 that turned out to change nothing meaningful.
 *
 * This looks the section up before vanilla does. If even creation returns nothing - which is exactly
 * the state that produces the exception - it records the section's coordinates once per section and
 * says whether it lies inside the vanilla band, below it, or above it. That tells apart "this mod
 * announced a section the engine has no storage for" from "storage that existed was dropped", which
 * are different bugs with different fixes.
 *
 * Diagnostics only. It changes no behaviour beyond doing the lookup a second time.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LightStorageDiagnosticMixin {

    @org.spongepowered.asm.mixin.gen.Invoker("getDataLayer")
    abstract DataLayer cubicchunks$lookupLayer(long sectionNode, boolean createIfMissing);

    @Inject(method = "getStoredLevel(J)I", at = @At("HEAD"))
    private void cubicchunks$describeMissingLayer(long blockNode, CallbackInfoReturnable<Integer> info) {
        long sectionNode = SectionPos.blockToSection(blockNode);
        DataLayer existing = cubicchunks$lookupLayer(sectionNode, true);
        if (existing != null) {
            return;
        }
        SectionPos section = SectionPos.of(sectionNode);
        LightProbe.recordMissing(section.x(), section.y(), section.z());
    }
}
