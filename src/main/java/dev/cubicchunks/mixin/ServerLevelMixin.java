package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.api.CubicLevelBounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Overrides the Y-range accessors on {@link ServerLevel} (these are normally inherited as
 * interface defaults from {@code LevelReader} delegating to {@code dimensionType()}). Declaring
 * real methods here beats the interface default.
 *
 * <p>Bounds state is resolved lazily on first access so any Y queries during construction still
 * observe vanilla bounds.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements CubicLevelBounds {

    @Unique private int cubicchunks$checkState = 0; // 0 = unresolved, 1 = cubic, -1 = plain
    @Unique private int cubicchunks$minY;
    @Unique private int cubicchunks$maxY;

    @Unique
    private boolean cubicchunks$resolve() {
        if (cubicchunks$checkState == 0) {
            CubicConfig.CubicLevelState state = CubicConfig.stateFor(((Level) (Object) this).dimension());
            if (state == null) {
                cubicchunks$checkState = -1;
            } else {
                cubicchunks$checkState = 1;
                cubicchunks$minY = state.minY();
                cubicchunks$maxY = state.maxY();
            }
        }
        return cubicchunks$checkState == 1;
    }

    @Override
    public boolean cubicchunks$isCubic() {
        return cubicchunks$resolve();
    }

    @Override
    public int cubicchunks$minY() {
        return cubicchunks$minY;
    }

    @Override
    public int cubicchunks$maxY() {
        return cubicchunks$maxY;
    }

    @Unique
    private DimensionType cubicchunks$vanillaType() {
        return ((Level) (Object) this).dimensionType();
    }

    /** Class-level override of the {@code LevelHeightAccessor} interface default. */
    public int getMinY() {
        return cubicchunks$resolve() ? cubicchunks$minY : cubicchunks$vanillaType().minY();
    }

    /** Class-level override of the {@code LevelReader} interface default. */
    public int getHeight() {
        return cubicchunks$resolve()
                ? cubicchunks$maxY - cubicchunks$minY
                : cubicchunks$vanillaType().height();
    }
}
