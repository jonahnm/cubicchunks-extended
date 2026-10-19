package dev.cubicchunks.gen;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import dev.cubicchunks.core.ExtensionCubeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * {@link WorldGenLevel} view used while placing repeated stratum features in extension cubes.
 *
 * <p>Reads delegate to the real {@link ServerLevel}. Writes only succeed inside extension cubes
 * whose sections are already generated (returning {@code false} mirrors vanilla's write-radius
 * clipping for out-of-region feature writes). Height sampling keeps the vanilla band of this
 * dimension, so feature distributions are band-exact and are then stratum-shifted by the planner;
 * the section-resolution and build-height checks features use to clip their writes deliberately
 * use the extended range, so those writes can land below the band.
 */
public final class CubeGenAccess implements WorldGenLevel {

    private final ServerLevel level;
    private final ExtensionCubeManager manager;
    private final int bandMinY;
    private final int bandHeight;

    public CubeGenAccess(ServerLevel level, ExtensionCubeManager manager, int bandMinY, int bandHeight) {
        this.level = level;
        this.manager = manager;
        this.bandMinY = bandMinY;
        this.bandHeight = bandHeight;
    }

    /**
     * Height providers see the VANILLA band bounds, so placement distributions are band-exact;
     * the planner then tiles/shifts the resulting positions downward.
     *
     * <p>Everything else about the Y range — section resolution and the build-height checks that
     * features use to clip their writes ({@code OreFeature} calls {@code isOutsideBuildHeight}, and
     * {@code BulkSectionAccess} resolves the target section through {@code getSectionIndex} plus
     * {@code getSectionsCount}) — must use the EXTENDED range of the real level, otherwise feature
     * writes below the band are silently dropped and no ore ever appears under {@code bandMinY}.
     */
    @Override
    public int getMinY() {
        return this.bandMinY;
    }

    @Override
    public int getHeight() {
        return this.bandHeight;
    }

    // ------------------------------------------------------------ section/height clipping

    /** Delegated to the real level: deep writes must resolve to the column's real sections. */
    @Override
    public int getMaxY() {
        return this.level.getMaxY();
    }

    @Override
    public int getSectionsCount() {
        return this.level.getSectionsCount();
    }

    @Override
    public int getMinSectionY() {
        return this.level.getMinSectionY();
    }

    @Override
    public int getMaxSectionY() {
        return this.level.getMaxSectionY();
    }

    @Override
    public int getSectionIndex(int y) {
        return this.level.getSectionIndex(y);
    }

    @Override
    public int getSectionIndexFromSectionY(int sectionY) {
        return this.level.getSectionIndexFromSectionY(sectionY);
    }

    @Override
    public int getSectionYFromSectionIndex(int sectionIndex) {
        return this.level.getSectionYFromSectionIndex(sectionIndex);
    }

    @Override
    public boolean isOutsideBuildHeight(int y) {
        return this.level.isOutsideBuildHeight(y);
    }

    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        return this.level.isOutsideBuildHeight(pos);
    }

    // ------------------------------------------------------------ cubic interception

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int limit) {
        return this.manager.cubeSetBlockFromFeature(pos, state);
    }

    @Override
    public boolean ensureCanWrite(BlockPos pos) {
        return this.manager.cubeCanAcceptWrite(pos);
    }

    @Override
    public void setCurrentlyGenerating(@Nullable Supplier<String> name) {
        // no-op
    }

    // Disallowed mutation primitives: stratum features never remove blocks.
    @Override
    public boolean removeBlock(BlockPos pos, boolean drop) {
        return false;
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int limit) {
        return false;
    }

    // ------------------------------------------------------------ pure delegation to the server level

    @Override
    public long getSeed() {
        return this.level.getSeed();
    }

    @Override
    public ServerLevel getLevel() {
        return this.level;
    }

    @Override
    public ChunkSource getChunkSource() {
        return this.level.getChunkSource();
    }

    /**
     * Passes {@code requireChunk} through unchanged. Optional lookups stay non-loading (that is what
     * keeps feature writes from pulling neighbour columns into the tick loop), but required lookups
     * must really return a chunk: 26.x's {@code BulkSectionAccess} (now used by OreFeature) derefs
     * the result of {@code getChunk(x, z)} without a null check.
     */
    @Nullable
    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean requireChunk) {
        return this.level.getChunk(x, z, status, requireChunk);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.level.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.level.getFluidState(pos);
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.level.getBlockEntity(pos);
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return this.level.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public BiomeManager getBiomeManager() {
        return this.level.getBiomeManager();
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return this.level.getChunkSource().getChunkNow(x, z) != null;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSeaLevel() {
        return this.level.getChunkSource().getGenerator().getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return this.level.dimensionType();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return this.level.enabledFeatures();
    }

    @Override
    public net.minecraft.world.Difficulty getDifficulty() {
        return this.level.getDifficulty();
    }

    @Override
    public RandomSource getRandom() {
        return this.level.getRandom();
    }

    @Nullable
    @Override
    public MinecraftServer getServer() {
        return this.level.getServer();
    }

    @Override
    public LevelData getLevelData() {
        return this.level.getLevelData();
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return this.level.getCurrentDifficultyAt(pos);
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return this.level.getBlockTicks();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return this.level.getFluidTicks();
    }

    @Override
    public long nextSubTickCount() {
        return this.level.nextSubTickCount();
    }

    @Override
    public RegistryAccess registryAccess() {
        return this.level.registryAccess();
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return this.level.getHeight(type, x, z);
    }

    @Override
    public int getSkyDarken() {
        return this.level.getSkyDarken();
    }

    @Override
    public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() {
        return this.level.environmentAttributes();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.level.getLightEngine();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return this.level.getWorldBorder();
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        return this.level.isStateAtPosition(pos, predicate);
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        return this.level.isFluidAtPosition(pos, predicate);
    }

    @Override
    public <T extends BlockEntity> java.util.Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
        return this.level.getBlockEntity(pos, type);
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) {
        return this.level.getEntities(test, box, predicate);
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity excluded, AABB box, Predicate<? super Entity> predicate) {
        return this.level.getEntities(excluded, box, predicate);
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
        return this.level.getEntityCollisions(entity, box);
    }

    @Override
    public List<? extends Player> players() {
        return this.level.players();
    }

    @Override
    public net.minecraft.world.level.BlockGetter getChunkForCollisions(int x, int z) {
        return this.level.getChunkForCollisions(x, z);
    }

    @Override
    public void addParticle(net.minecraft.core.particles.ParticleOptions particle, double x, double y, double z, double vx, double vy, double vz) {
        // worldgen particles are not emitted
    }

    @Override
    public void levelEvent(@Nullable Entity source, int event, BlockPos pos, int data) {
        this.level.levelEvent(source, event, pos, data);
    }

    @Override
    public void playSound(@Nullable Entity except, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
        this.level.playSound(except, pos, sound, source, volume, pitch);
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
        this.level.gameEvent(event, pos, context);
    }
}
