package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.api.CubicLevelBounds;
import dev.cubicchunks.net.CubicSyncState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Client-side counterpart of {@link ServerLevelMixin}: overrides the Y-range accessors on
 * {@link ClientLevel} using the bounds pushed by the server (see {@link CubicSyncState}).
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin implements CubicLevelBounds {

    @org.spongepowered.asm.mixin.Unique
    private DimensionType cubicchunks$vanillaType() {
        return ((Level) (Object) this).dimensionType();
    }

    @Override
    public boolean cubicchunks$isCubic() {
        return cubicchunks$boundsOrNull() != null;
    }

    /**
     * Bounds come from the server payload when available; otherwise from the local config, so
     * same-config clients decode chunk data correctly even if a packet raced ahead of the payload.
     */
    @org.spongepowered.asm.mixin.Unique
    private CubicSyncState.Bounds cubicchunks$boundsOrNull() {
        CubicSyncState.Bounds b = CubicSyncState.get(((Level) (Object) this).dimension().location());
        if (b != null) {
            return b;
        }
        CubicConfig.CubicLevelState s = CubicConfig.stateFor(((Level) (Object) this).dimension());
        return s == null ? null : new CubicSyncState.Bounds(s.minY(), s.maxY());
    }

    @Override
    public int cubicchunks$minY() {
        CubicSyncState.Bounds b = cubicchunks$boundsOrNull();
        return b == null ? Integer.MIN_VALUE : b.minY();
    }

    @Override
    public int cubicchunks$maxY() {
        CubicSyncState.Bounds b = cubicchunks$boundsOrNull();
        return b == null ? Integer.MIN_VALUE : b.maxY();
    }

    /** Class-level override of the {@code LevelReader} interface default. */
    public int getMinBuildHeight() {
        CubicSyncState.Bounds b = cubicchunks$boundsOrNull();
        return b == null ? cubicchunks$vanillaType().minY() : b.minY();
    }

    /** Class-level override of the {@code LevelReader} interface default. */
    public int getHeight() {
        CubicSyncState.Bounds b = cubicchunks$boundsOrNull();
        return b == null ? cubicchunks$vanillaType().height() : b.maxY() - b.minY();
    }
}
