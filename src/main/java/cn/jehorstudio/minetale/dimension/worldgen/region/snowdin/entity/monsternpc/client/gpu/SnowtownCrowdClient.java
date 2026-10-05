package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network.SnowtownCrowdPopulationPayload;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network.SnowtownCrowdPopulationPayload.SurfacePopulation;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

// 以“固定扇区内的规划连通面切片”为人口身份；玩家移动只改变预取与 GPU 工作集
@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class SnowtownCrowdClient {
    static final int MAX_AGENT_COUNT_PER_SECTOR = 1024;
    static final ContextKey<List<FrameState>> FRAME_STATES = new ContextKey<>(
            ResourceLocation.fromNamespaceAndPath(
                    MineTale.MODID,
                    "snowtown_gpu_crowd_sectors"));

    private static final int MAX_REQUESTED_SECTORS = 48;
    private static final int MAX_GPU_WORKING_SECTORS = MAX_REQUESTED_SECTORS;
    private static final int MAX_RETAINED_SECTORS = 64;
    private static final int PREFETCH_SECTOR_RINGS = 1;
    private static final int MUTATION_INTERVAL_TICKS = 10;
    private static final int OFFSCREEN_RETENTION_TICKS = 1200;
    private static final int BUILD_RETRY_TICKS = 20;
    private static final int BLOCK_CHANGE_DEBOUNCE_TICKS = 2;
    private static final int MAX_CAPTURE_ATTEMPTS_PER_TICK = 3;
    private static final int OFFSCREEN_SIMULATION_INTERVAL_TICKS = 20;
    private static final float OFFSCREEN_SIMULATION_STEP_SECONDS = 0.1F;
    private static final int GPU_VISIBILITY_GRACE_TICKS = 5;
    private static final int MIN_SAFE_SPAWN_DESTINATIONS = 8;
    private static final int MAX_BIRTH_AGENTS_PER_MUTATION = 32;
    private static final int SPAWN_DESTINATION_REUSE_TICKS = 40;
    private static final double SPAWN_VISIBILITY_MARGIN = 5.0D;

    private static final Map<SectorKey, SectorRuntime> SECTORS = new LinkedHashMap<>();

    private static ClientLevel activeLevel;
    private static SnowtownCrowdPopulationPayload populationSnapshot;
    private static CompletableFuture<SectorBuildResult> sectorBuild;
    private static SectorKey buildingSector;
    private static int buildSerial;
    private static int nextGeneration = 1;
    private static long clientTick;
    private static long nextMutationTick;
    private static long nextLightRefreshTick = Long.MAX_VALUE;
    private static int lastWorkingSectorCount;

    private SnowtownCrowdClient() {
    }

    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(
                SnowtownCrowdPopulationPayload.TYPE,
                SnowtownCrowdClient::handlePopulation
        );
    }

    // 资源重载使全部 GPU 派生物失效，下一次渲染再按需创建。
    public static void invalidateRenderResources() {
        clearSectorPool();
    }

    private static void handlePopulation(
            SnowtownCrowdPopulationPayload payload,
            IPayloadContext context
    ) {
        Minecraft.getInstance().execute(() -> applyPopulation(payload));
    }

    @SubscribeEvent
    public static void extractRenderState(ExtractLevelRenderStateEvent event) {
        if (!GpuCrowdSupportProbe.isAvailable() || event.getLevel() != activeLevel) {
            lastWorkingSectorCount = 0;
            event.getRenderState().setRenderData(FRAME_STATES, List.of());
            return;
        }

        float realtimeDeltaSeconds = Math.clamp(
                event.getDeltaTracker().getRealtimeDeltaTicks() / 20.0F,
                0.0F,
                0.05F
        );
        Vec3 camera = event.getCamera().getPosition();
        refreshOneLightField(event.getLevel(), camera);
        double visualRange = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0D;
        double visualRangeSquared = visualRange * visualRange;
        List<SectorRuntime> workingCandidates = new ArrayList<>();
        for (SectorRuntime sector : SECTORS.values()) {
            AABB bounds = sector.renderBounds();
            boolean onScreen = event.getFrustum().isVisible(bounds)
                    && horizontalDistanceToBoundsSquared(camera, bounds)
                    <= visualRangeSquared;
            sector.visibilityKnown = true;
            sector.onScreen = onScreen;
            if (sector.wanted) {
                sector.updateSafeSpawnDestinations(
                        event,
                        camera,
                        visualRangeSquared
                );
            }
            if (sector.onScreen) {
                sector.lastVisibleTick = clientTick;
                sector.nextOffscreenSimulationTick = firstOffscreenSimulationTick(sector.key);
            }
            if (isGpuWorkingCandidate(sector)) {
                workingCandidates.add(sector);
            }
        }

        workingCandidates.sort(Comparator
                .comparing((SectorRuntime sector) -> !sector.onScreen)
                .thenComparingDouble(sector -> horizontalDistanceToBoundsSquared(
                        camera,
                        sector.renderBounds()))
                .thenComparing(sector -> sector.key));
        lastWorkingSectorCount = Math.min(
                MAX_GPU_WORKING_SECTORS,
                workingCandidates.size());

        List<FrameState> frames = new ArrayList<>(lastWorkingSectorCount);
        for (int index = 0; index < lastWorkingSectorCount; index++) {
            SectorRuntime sector = workingCandidates.get(index);

            float simulationDeltaSeconds = 0.0F;
            if (sector.onScreen) {
                simulationDeltaSeconds = realtimeDeltaSeconds;
            } else if (clientTick >= sector.nextOffscreenSimulationTick) {
                simulationDeltaSeconds = OFFSCREEN_SIMULATION_STEP_SECONDS;
                sector.nextOffscreenSimulationTick =
                        clientTick + OFFSCREEN_SIMULATION_INTERVAL_TICKS;
            }
            sector.elapsedSeconds += simulationDeltaSeconds;
            Vec3 anchor = sector.staticField.worldAnchor();
            frames.add(new FrameState(
                    sector.key,
                    sector.agentCount,
                    sector.visibleAgentCount,
                    sector.targetAgentCount,
                    (float)(anchor.x - camera.x),
                    (float)(anchor.y - camera.y),
                    (float)(anchor.z - camera.z),
                    simulationDeltaSeconds,
                    sector.elapsedSeconds,
                    sector.generation,
                    sector.geometryGeneration,
                    sector.lightGeneration,
                    sector.navigationGeneration,
                    0,
                    0.0F,
                    0.0F,
                    sector.spawnGeneration,
                    sector.spawnDestinationPixels,
                    sector.staticField,
                    sector.onScreen
            ));
        }
        frames.sort(Comparator.comparing(FrameState::sectorKey));
        event.getRenderState().setRenderData(FRAME_STATES, List.copyOf(frames));
    }

    @SubscribeEvent
    public static void renderAfterEntities(RenderLevelStageEvent.AfterEntities event) {
        if(!GpuCrowdSupportProbe.isAvailable()){
            return;
        }
        List<FrameState> frames = event.getLevelRenderState().getRenderData(FRAME_STATES);
        SnowtownGpuCrowdRenderer.INSTANCE.renderResidents(event, frames);
    }

    // 输入事件只消费最近一次 GPU 拾取结果
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if(!GpuCrowdSupportProbe.isAvailable()){
            return;
        }
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (SnowtownGpuCrowdRenderer.INSTANCE.tryInteractHovered()) {
            event.setCanceled(true);
            event.setSwingHand(true);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if(!GpuCrowdSupportProbe.isAvailable()){
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (activeLevel != minecraft.level) {
            resetForLevel(minecraft.level);
        }

        clientTick++;
        completeSectorBuild();
        if (minecraft.player == null) {
            return;
        }

        refreshWantedSectors(minecraft);
        releaseDormantStaticFields();
        pruneUnbornUnwantedSectors();
        startNextSectorBuild(minecraft.level, minecraft.player.position());
        if (clientTick >= nextMutationTick) {
            processOneOffscreenMutation(minecraft.player.position());
            nextMutationTick = clientTick + MUTATION_INTERVAL_TICKS;
        }
    }

    // 预测与服务端方块更新共用局部失效入口，只重建覆盖坐标的固定扇区。
    public static void onClientBlockChanged(ClientLevel level, BlockPos position) {
        if(!GpuCrowdSupportProbe.isAvailable()){
            return;
        }
        if (level != activeLevel) {
            return;
        }
        for (SectorRuntime sector : SECTORS.values()) {
            if (sector.mayBeAffectedBy(position)) {
                sector.blockRevision++;
                sector.rebuildAfterTick = clientTick + BLOCK_CHANGE_DEBOUNCE_TICKS;
            }
            nextLightRefreshTick = Math.min(
                    nextLightRefreshTick,
                    sector.markLightDirty(position));
        }
    }

    private static void refreshOneLightField(ClientLevel level, Vec3 player) {
        if (clientTick < nextLightRefreshTick) {
            return;
        }
        SectorRuntime candidate = SECTORS.values().stream()
                .filter(SectorRuntime::needsLightRefresh)
                .min(Comparator
                        .comparing((SectorRuntime sector) -> !sector.onScreen)
                        .thenComparingDouble(sector -> sector.centerDistanceSquared(player))
                        .thenComparing(sector -> sector.key))
                .orElse(null);
        if (candidate == null) {
            scheduleNextLightRefresh();
            return;
        }
        SnowtownGpuCrowdStaticField.LightRefresh refresh = candidate.refreshLight(level);
        scheduleNextLightRefresh();
        if (nextLightRefreshTick <= clientTick) {
            // 每 Tick 最多上传一个受影响扇区
            nextLightRefreshTick = clientTick + 1L;
        }
        if (refresh.changedCells() > 0) {
            MineTale.LOGGER.debug(
                    "Snowtown GPU 局部光照场已刷新：sector={}，sampled={}，changed={}，capture={}us",
                    candidate.key,
                    refresh.sampledCells(),
                    refresh.changedCells(),
                    refresh.captureNanos() / 1_000L);
        }
    }

    private static void scheduleNextLightRefresh() {
        nextLightRefreshTick = SECTORS.values().stream()
                .filter(SectorRuntime::hasPendingLightRefresh)
                .mapToLong(sector -> sector.lightRefreshAfterTick)
                .min()
                .orElse(Long.MAX_VALUE);
    }

    @SubscribeEvent
    public static void renderDebugLine(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (SECTORS.isEmpty() || !minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        event.getGuiGraphics().drawString(
                minecraft.font,
                SnowtownGpuCrowdRenderer.INSTANCE.debugLine()
                        + " cached=" + SECTORS.size()
                        + " working=" + lastWorkingSectorCount
                        + " requested=" + countWantedSectors()
                        + " ready=" + countReadySectors()
                        + (sectorBuild == null ? "" : " building=1"),
                2,
                event.getGuiGraphics().guiHeight() - 22,
                0xFF80E8FF,
                true
        );
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        populationSnapshot = null;
        activeLevel = null;
        clearSectorPool();
    }

    // 服务端只同步镇身份与建筑容量，visual agent 坐标完全归客户端模拟。
    public static void applyPopulation(SnowtownCrowdPopulationPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && activeLevel != minecraft.level) {
            resetForLevel(minecraft.level);
        }
        populationSnapshot = payload;
        if (!payload.active()) {
            SECTORS.values().forEach(sector -> sector.wanted = false);
            nextMutationTick = Math.min(nextMutationTick, clientTick);
        }
    }

    private static void refreshWantedSectors(Minecraft minecraft) {
        SECTORS.values().forEach(sector -> sector.wanted = false);
        SnowtownCrowdPopulationPayload snapshot = populationSnapshot;
        if (snapshot == null || !snapshot.active() || minecraft.player == null) {
            return;
        }

        Vec3 player = minecraft.player.position();
        int renderDistanceBlocks = minecraft.options.getEffectiveRenderDistance() * 16;
        double requestRadius = renderDistanceBlocks
                + PREFETCH_SECTOR_RINGS * SnowtownGpuCrowdStaticField.SIZE;
        double requestRadiusSquared = requestRadius * requestRadius;
        Map<SectorKey, SurfaceDemand> demandBySector = new LinkedHashMap<>();
        for (SnowtownCrowdPopulationPayload.TownPopulation town : snapshot.towns()) {
            for (SurfacePopulation surface : town.surfaces()) {
                if (surface.outdoorPopulation() <= 0) {
                    continue;
                }
                SectorKey key = new SectorKey(
                        surface.sectorX(),
                        surface.sectorZ(),
                        town.areaX(),
                        town.areaZ(),
                        town.componentIndex(),
                        surface.surfaceId()
                );
                // 同一规划切片只有一个权威配额，重复网络项不能累加。
                demandBySector.putIfAbsent(key, SurfaceDemand.from(surface));
            }
        }
        List<SectorCandidate> candidates = new ArrayList<>();
        for (Map.Entry<SectorKey, SurfaceDemand> entry : demandBySector.entrySet()) {
            SectorKey key = entry.getKey();
            double distanceSquared = distanceToSectorBoundsSquared(
                    player,
                    key.sectorX(),
                    key.sectorZ()
            );
            if (distanceSquared <= requestRadiusSquared) {
                candidates.add(new SectorCandidate(
                        key,
                        entry.getValue(),
                        distanceSquared));
            }
        }
        candidates.sort(Comparator
                .comparingDouble(SectorCandidate::distanceSquared)
                .thenComparing(SectorCandidate::key));

        int requested = Math.min(MAX_REQUESTED_SECTORS, candidates.size());
        for (int index = 0; index < requested; index++) {
            SectorCandidate candidate = candidates.get(index);
            SectorRuntime sector = SECTORS.computeIfAbsent(
                    candidate.key(),
                    ignored -> new SectorRuntime(candidate.key(), candidate.demand())
            );
            sector.wanted = true;
            sector.lastWantedTick = clientTick;
            sector.updatePopulationFacts(candidate.demand());
        }
    }

    private static void pruneUnbornUnwantedSectors() {
        SECTORS.entrySet().removeIf(entry -> {
            SectorRuntime sector = entry.getValue();
            return !sector.activated
                    && !sector.wanted
                    && !entry.getKey().equals(buildingSector);
        });
    }

    private static void releaseDormantStaticFields() {
        for (SectorRuntime sector : SECTORS.values()) {
            if (sector.staticField == null
                    || sector.wanted
                    || sector.key.equals(buildingSector)) {
                continue;
            }
            sector.releaseHeavyState();
        }
    }

    private static void startNextSectorBuild(ClientLevel level, Vec3 player) {
        if (sectorBuild != null) {
            return;
        }
        List<SectorRuntime> candidates = SECTORS.values().stream()
                .filter(sector -> sector.nextBuildAttemptTick <= clientTick)
                .filter(SnowtownCrowdClient::needsBuild)
                .sorted(buildPriority(player))
                .toList();

        int attempts = 0;
        for (SectorRuntime sector : candidates) {
            if (attempts++ >= MAX_CAPTURE_ATTEMPTS_PER_TICK) {
                break;
            }
            if (beginSectorBuild(level, sector)) {
                return;
            }
            sector.nextBuildAttemptTick = clientTick + BUILD_RETRY_TICKS;
        }
    }

    private static boolean needsBuild(SectorRuntime sector) {
        if (!sector.wanted) {
            return false;
        }
        if (sector.staticField == null) {
            return true;
        }
        return sector.rebuildAfterTick <= clientTick
                && (sector.geometryBlockRevision < sector.blockRevision
                || sector.navigationBlockRevision < sector.blockRevision);
    }

    private static Comparator<SectorRuntime> buildPriority(Vec3 player) {
        return (left, right) -> {
            int dirtyComparison = Boolean.compare(
                    left.staticField != null,
                    right.staticField != null
            );
            if (dirtyComparison != 0) {
                return dirtyComparison;
            }
            int visibilityComparison = Integer.compare(
                    visibilityPriority(left),
                    visibilityPriority(right)
            );
            if (visibilityComparison != 0) {
                return visibilityComparison;
            }
            int distanceComparison = Double.compare(
                    left.centerDistanceSquared(player),
                    right.centerDistanceSquared(player)
            );
            return distanceComparison != 0
                    ? distanceComparison
                    : left.key.compareTo(right.key);
        };
    }

    private static int visibilityPriority(SectorRuntime sector) {
        if (sector.visibilityKnown && sector.onScreen) {
            return 0;
        }
        return sector.visibilityKnown ? 2 : 1;
    }

    private static boolean beginSectorBuild(ClientLevel level, SectorRuntime sector) {
        boolean rebuild = sector.staticField != null;
        int requestBlockRevision = sector.blockRevision;
        Optional<SnowtownGpuCrowdStaticField.Geometry> captured;
        int[] previousDestinations = null;
        if (rebuild) {
            if (!sector.staticField.hasAllChunks(level)) {
                return false;
            }
            captured = sector.staticField.recaptureGeometry(level);
            if (captured.isPresent()) {
                previousDestinations = sector.staticField.destinationPixels().clone();
                sector.staticField = sector.staticField.withGeometry(captured.get());
                sector.geometryGeneration++;
                sector.markCapturedLightForUpload();
                sector.geometryBlockRevision = requestBlockRevision;
            }
        } else {
            captured = SnowtownGpuCrowdStaticField.captureSectorGeometry(
                    level,
                    sector.key.sectorX(),
                    sector.key.sectorZ(),
                    sector.preferredSeedX,
                    sector.preferredHeight,
                    sector.preferredSeedZ,
                    sector.planningMaskWords
            );
        }
        if (captured.isEmpty()) {
            return false;
        }

        SnowtownGpuCrowdStaticField.Geometry geometry = captured.get();
        int[] stableDestinations = previousDestinations;
        int serial = ++buildSerial;
        buildingSector = sector.key;
        sectorBuild = CompletableFuture.supplyAsync(
                () -> new SectorBuildResult(
                        serial,
                        sector.key,
                        rebuild,
                        requestBlockRevision,
                        rebuild
                                ? SnowtownGpuCrowdFieldCompiler.recompile(
                                        geometry,
                                        stableDestinations)
                                : SnowtownGpuCrowdFieldCompiler.compile(geometry)
                ),
                Util.backgroundExecutor()
        );
        return true;
    }

    private static void completeSectorBuild() {
        if (sectorBuild == null || !sectorBuild.isDone()) {
            return;
        }
        CompletableFuture<SectorBuildResult> completed = sectorBuild;
        SectorKey completedKey = buildingSector;
        sectorBuild = null;
        buildingSector = null;
        try {
            SectorBuildResult result = completed.join();
            if (result.serial() != buildSerial) {
                return;
            }
            SectorRuntime sector = SECTORS.get(result.key());
            if (sector == null) {
                return;
            }
            if (!sector.wanted) {
                if (!sector.activated) {
                    SECTORS.remove(result.key());
                }
                return;
            }
            SnowtownGpuCrowdStaticField completedField = result.field();
            if (result.rebuild() && sector.staticField != null) {
                completedField = completedField.withLightPixels(
                        sector.staticField.lightPixels());
            }
            sector.staticField = completedField;
            if (sector.activated
                    && sector.spawnDestinationPixels.length
                    != SnowtownGpuCrowdStaticField.SPAWN_POINT_COUNT) {
                sector.installIdentitySpawnDestinations();
            }
            sector.geometryBlockRevision = result.blockRevision();
            sector.navigationBlockRevision = result.blockRevision();
            if (result.rebuild()) {
                sector.navigationGeneration++;
            } else {
                sector.geometryGeneration = 1;
                sector.lightGeneration = 1;
                sector.navigationGeneration = 1;
            }
            sector.nextBuildAttemptTick = clientTick;
            MineTale.LOGGER.debug(
                    "Snowtown GPU 固定扇区编译完成：sector={}，rebuild={}，{}",
                    result.key(),
                    result.rebuild(),
                    result.field().diagnostics()
            );
        } catch (CompletionException failure) {
            SectorRuntime sector = SECTORS.get(completedKey);
            if (sector != null) {
                sector.nextBuildAttemptTick = clientTick + BUILD_RETRY_TICKS;
            }
            MineTale.LOGGER.error(
                    "Snowtown GPU 固定扇区后台编译失败：sector={}",
                    completedKey,
                    failure.getCause()
            );
        }
    }

    private static void processOneOffscreenMutation(Vec3 player) {
        List<SectorRuntime> offscreen = SECTORS.values().stream()
                .filter(SectorRuntime::isKnownOffscreen)
                .sorted(farthestFirst(player))
                .toList();
        int activatedCount = countActivatedSectors();
        int requestedActivatedCount = countActivatedWantedSectors();

        for (SectorRuntime sector : offscreen) {
            if (!sector.activated) {
                continue;
            }
            boolean expired = !sector.wanted
                    && clientTick - sector.lastWantedTick >= OFFSCREEN_RETENTION_TICKS;
            boolean overflow = !sector.wanted
                    && activatedCount > MAX_RETAINED_SECTORS;
            if (expired || overflow) {
                requireKnownOffscreen(sector, "回收");
                SECTORS.remove(sector.key);
                activatedCount--;
                MineTale.LOGGER.debug(
                        "Snowtown GPU 扇区已在视锥外回收：sector={}，reason={}",
                        sector.key,
                        overflow ? "overflow" : "expired"
                );
                break;
            }
        }

        for (SectorRuntime sector : offscreen) {
            if (!SECTORS.containsKey(sector.key)
                    || !sector.activated
                    || sector.agentCount <= sector.targetAgentCount) {
                continue;
            }
            requireKnownOffscreen(sector, "人口回收");
            sector.agentCount = sector.targetAgentCount;
            sector.visibleAgentCount = sector.agentCount;
            return;
        }

        List<SectorRuntime> birthCandidates = SECTORS.values().stream()
                .filter(SectorRuntime::hasSafeBirthLocations)
                .filter(sector -> sector.wanted
                        && sector.staticField != null
                        && sector.agentCount < sector.targetAgentCount
                        && sector.geometryBlockRevision == sector.blockRevision
                        && sector.navigationBlockRevision == sector.blockRevision)
                .sorted(birthPriority(player))
                .toList();
        for (SectorRuntime sector : birthCandidates) {
            if (!sector.activated
                    && requestedActivatedCount >= MAX_REQUESTED_SECTORS) {
                continue;
            }
            requireSafeBirthLocations(sector);
            int requestedBirths = Math.min(
                    MAX_BIRTH_AGENTS_PER_MUTATION,
                    sector.targetAgentCount - sector.agentCount
            );
            int births = sector.prepareSpawnDestinations(
                    sector.agentCount,
                    requestedBirths
            );
            if (births <= 0) {
                continue;
            }
            boolean firstCohort = !sector.activated;
            if (firstCohort) {
                sector.activated = true;
                sector.generation = nextGeneration++;
                sector.elapsedSeconds = 0.0F;
            }
            sector.agentCount += births;
            sector.visibleAgentCount = sector.agentCount;
            sector.nextOffscreenSimulationTick =
                    firstOffscreenSimulationTick(sector.key);
            MineTale.LOGGER.debug(
                    "Snowtown GPU 扇区人口批次已提交：sector={}，新增={}，居民={}/{}，安全目的地={}",
                    sector.key,
                    births,
                    sector.agentCount,
                    sector.targetAgentCount,
                    sector.safeSpawnDestinationCount
            );
            return;
        }

    }

    private static Comparator<SectorRuntime> birthPriority(Vec3 player) {
        return (left, right) -> {
            long leftScaled = (long)left.agentCount * right.targetAgentCount;
            long rightScaled = (long)right.agentCount * left.targetAgentCount;
            int fillComparison = Long.compare(leftScaled, rightScaled);
            return fillComparison != 0
                    ? fillComparison
                    : farthestFirst(player).compare(left, right);
        };
    }

    private static Comparator<SectorRuntime> farthestFirst(Vec3 player) {
        return (left, right) -> {
            int distanceComparison = Double.compare(
                    right.centerDistanceSquared(player),
                    left.centerDistanceSquared(player)
            );
            return distanceComparison != 0
                    ? distanceComparison
                    : left.key.compareTo(right.key);
        };
    }

    private static void requireKnownOffscreen(SectorRuntime sector, String operation) {
        if (!sector.isKnownOffscreen()) {
            throw new IllegalStateException(
                    "Snowtown GPU 扇区" + operation + "越过视锥外硬门：" + sector.key);
        }
    }

    private static void requireSafeBirthLocations(SectorRuntime sector) {
        if (!sector.hasSafeBirthLocations()) {
            throw new IllegalStateException(
                    "Snowtown GPU 扇区出生越过屏幕外出生点硬门：" + sector.key);
        }
    }

    private static void resetForLevel(ClientLevel level) {
        clearSectorPool();
        activeLevel = level;
        populationSnapshot = null;
        nextMutationTick = clientTick;
        lastWorkingSectorCount = 0;
    }

    private static void clearSectorPool() {
        cancelSectorBuild();
        SECTORS.clear();
        lastWorkingSectorCount = 0;
        nextLightRefreshTick = Long.MAX_VALUE;
        SnowtownGpuCrowdRenderer.INSTANCE.close();
    }

    private static void cancelSectorBuild() {
        buildSerial++;
        if (sectorBuild != null) {
            sectorBuild.cancel(false);
        }
        sectorBuild = null;
        buildingSector = null;
    }

    private static int countWantedSectors() {
        return (int)SECTORS.values().stream().filter(sector -> sector.wanted).count();
    }

    private static int countReadySectors() {
        return (int)SECTORS.values().stream()
                .filter(sector -> sector.staticField != null)
                .count();
    }

    private static int countActivatedSectors() {
        return (int)SECTORS.values().stream().filter(sector -> sector.activated).count();
    }

    private static int countActivatedWantedSectors() {
        return (int)SECTORS.values().stream()
                .filter(sector -> sector.activated && sector.wanted)
                .count();
    }

    private static boolean isGpuWorkingCandidate(SectorRuntime sector) {
        return sector.activated
                && sector.staticField != null
                && sector.wanted
                && sector.visibilityKnown
                && (sector.onScreen
                || clientTick - sector.lastVisibleTick <= GPU_VISIBILITY_GRACE_TICKS);
    }

    private static long firstOffscreenSimulationTick(SectorKey key) {
        return clientTick + 1L + Math.floorMod(
                key.hashCode(),
                OFFSCREEN_SIMULATION_INTERVAL_TICKS);
    }

    private static double distanceToSectorBoundsSquared(
            Vec3 position,
            int sectorX,
            int sectorZ
    ) {
        double minimumX = (double)sectorX * SnowtownGpuCrowdStaticField.SIZE;
        double minimumZ = (double)sectorZ * SnowtownGpuCrowdStaticField.SIZE;
        double maximumX = minimumX + SnowtownGpuCrowdStaticField.SIZE;
        double maximumZ = minimumZ + SnowtownGpuCrowdStaticField.SIZE;
        double deltaX = position.x < minimumX
                ? minimumX - position.x
                : position.x > maximumX ? position.x - maximumX : 0.0D;
        double deltaZ = position.z < minimumZ
                ? minimumZ - position.z
                : position.z > maximumZ ? position.z - maximumZ : 0.0D;
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    private static double horizontalDistanceToBoundsSquared(Vec3 position, AABB bounds) {
        double deltaX = position.x < bounds.minX
                ? bounds.minX - position.x
                : position.x > bounds.maxX ? position.x - bounds.maxX : 0.0D;
        double deltaZ = position.z < bounds.minZ
                ? bounds.minZ - position.z
                : position.z > bounds.maxZ ? position.z - bounds.maxZ : 0.0D;
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    record SectorKey(
            int sectorX,
            int sectorZ,
            int areaX,
            int areaZ,
            int componentIndex,
            int surfaceId
    ) implements Comparable<SectorKey> {
        @Override
        public int compareTo(SectorKey other) {
            int comparison = Integer.compare(this.sectorX, other.sectorX);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(this.sectorZ, other.sectorZ);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(this.areaX, other.areaX);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(this.areaZ, other.areaZ);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(this.componentIndex, other.componentIndex);
            return comparison != 0
                    ? comparison
                    : Integer.compare(this.surfaceId, other.surfaceId);
        }
    }

    record FrameState(
            SectorKey sectorKey,
            int agentCount,
            int visibleAgentCount,
            int targetAgentCount,
            float anchorX,
            float anchorY,
            float anchorZ,
            float deltaSeconds,
            float elapsedSeconds,
            int generation,
            int geometryGeneration,
            int lightGeneration,
            int navigationGeneration,
            int rebaseGeneration,
            float rebaseX,
            float rebaseZ,
            int spawnGeneration,
            int[] spawnDestinationPixels,
            SnowtownGpuCrowdStaticField staticField,
            boolean draw
    ) {
    }

    private static final class SectorRuntime {
        private final SectorKey key;
        private int preferredSeedX;
        private int preferredSeedZ;
        private float preferredHeight;
        private long[] planningMaskWords;
        private boolean wanted;
        private boolean activated;
        private boolean visibilityKnown;
        private boolean onScreen;
        private long lastWantedTick;
        private long lastVisibleTick;
        private long nextBuildAttemptTick;
        private long rebuildAfterTick;
        private long nextOffscreenSimulationTick;
        private int agentCount;
        private int visibleAgentCount;
        private int targetAgentCount;
        private int targetVisibleAgentCount;
        private int generation;
        private int geometryGeneration;
        private int lightGeneration;
        private int navigationGeneration;
        private int spawnGeneration;
        private int blockRevision;
        private int geometryBlockRevision;
        private int navigationBlockRevision;
        private int lightRevision;
        private int appliedLightRevision;
        private float elapsedSeconds;
        private long lightRefreshAfterTick;
        private SnowtownGpuCrowdStaticField.LightRegion dirtyLightRegion;
        private int safeSpawnDestinationCount;
        private int[] safeSpawnDestinationIds = new int[0];
        private final long[] lastSpawnTickByDestination = new long[
                SnowtownGpuCrowdStaticField.SPAWN_POINT_COUNT];
        private int[] spawnDestinationPixels = new int[0];
        private SnowtownGpuCrowdStaticField staticField;

        private SectorRuntime(SectorKey key, SurfaceDemand demand) {
            this.key = key;
            this.lastVisibleTick = Long.MIN_VALUE / 2L;
            Arrays.fill(this.lastSpawnTickByDestination, Long.MIN_VALUE / 2L);
            updatePopulationFacts(demand);
        }

        private void updatePopulationFacts(SurfaceDemand demand) {
            if (this.staticField == null) {
                this.preferredSeedX = demand.seedX();
                this.preferredHeight = demand.seedY();
                this.preferredSeedZ = demand.seedZ();
                this.planningMaskWords = demand.planningMaskWords().clone();
            }
            int requested = Math.clamp(
                    demand.outdoorPopulation(),
                    0,
                    MAX_AGENT_COUNT_PER_SECTOR
            );
            // GPU 只为实际户外样本分配状态槽
            this.targetAgentCount = requested;
            this.targetVisibleAgentCount = requested;
        }

        private boolean mayBeAffectedBy(BlockPos position) {
            if (this.staticField != null) {
                return this.staticField.mayBeAffectedBy(position);
            }
            int originX = this.key.sectorX() * SnowtownGpuCrowdStaticField.SIZE;
            int originZ = this.key.sectorZ() * SnowtownGpuCrowdStaticField.SIZE;
            return position.getX() >= originX
                    && position.getX() < originX + SnowtownGpuCrowdStaticField.SIZE
                    && position.getZ() >= originZ
                    && position.getZ() < originZ + SnowtownGpuCrowdStaticField.SIZE
                    && position.getY() >= this.preferredHeight
                            - SnowtownGpuCrowdStaticField.HEIGHT_RANGE - 2.0F
                    && position.getY() <= this.preferredHeight
                            + SnowtownGpuCrowdStaticField.HEIGHT_RANGE + 4.0F;
        }

        private long markLightDirty(BlockPos position) {
            if (this.staticField == null) {
                return Long.MAX_VALUE;
            }
            Optional<SnowtownGpuCrowdStaticField.LightRegion> affected =
                    this.staticField.lightRegionAffectedBy(position);
            if (affected.isEmpty()) {
                return Long.MAX_VALUE;
            }
            this.dirtyLightRegion = this.dirtyLightRegion == null
                    ? affected.get()
                    : this.dirtyLightRegion.union(affected.get());
            this.lightRevision++;
            this.lightRefreshAfterTick = clientTick + BLOCK_CHANGE_DEBOUNCE_TICKS;
            return this.lightRefreshAfterTick;
        }

        private boolean hasPendingLightRefresh() {
            return this.staticField != null
                    && this.dirtyLightRegion != null
                    && this.appliedLightRevision < this.lightRevision;
        }

        private boolean needsLightRefresh() {
            return hasPendingLightRefresh()
                    && this.lightRefreshAfterTick <= clientTick;
        }

        private SnowtownGpuCrowdStaticField.LightRefresh refreshLight(ClientLevel level) {
            SnowtownGpuCrowdStaticField.LightRefresh refresh =
                    this.staticField.refreshLight(level, this.dirtyLightRegion);
            this.staticField = refresh.field();
            this.appliedLightRevision = this.lightRevision;
            this.dirtyLightRegion = null;
            if (refresh.changedCells() > 0) {
                this.lightGeneration++;
            }
            return refresh;
        }

        private void markCapturedLightForUpload() {
            // 几何可能早于本帧光照传播完成，dirty region 须保留到 render-state 提取后再校正。
            this.lightGeneration++;
        }

        private AABB renderBounds() {
            if (this.staticField != null) {
                Vec3 anchor = this.staticField.worldAnchor();
                return new AABB(
                        anchor.x - SnowtownGpuCrowdStaticField.HALF_SIZE - 4.0D,
                        this.staticField.baseHeight()
                                - SnowtownGpuCrowdStaticField.HEIGHT_RANGE - 4.0D,
                        anchor.z - SnowtownGpuCrowdStaticField.HALF_SIZE - 4.0D,
                        anchor.x + SnowtownGpuCrowdStaticField.HALF_SIZE + 4.0D,
                        this.staticField.baseHeight()
                                + SnowtownGpuCrowdStaticField.HEIGHT_RANGE + 6.0D,
                        anchor.z + SnowtownGpuCrowdStaticField.HALF_SIZE + 4.0D
                );
            }
            double originX = (double)this.key.sectorX()
                    * SnowtownGpuCrowdStaticField.SIZE;
            double originZ = (double)this.key.sectorZ()
                    * SnowtownGpuCrowdStaticField.SIZE;
            return new AABB(
                    originX - 4.0D,
                    this.preferredHeight - SnowtownGpuCrowdStaticField.HEIGHT_RANGE - 4.0D,
                    originZ - 4.0D,
                    originX + SnowtownGpuCrowdStaticField.SIZE + 4.0D,
                    this.preferredHeight + SnowtownGpuCrowdStaticField.HEIGHT_RANGE + 6.0D,
                    originZ + SnowtownGpuCrowdStaticField.SIZE + 4.0D
            );
        }

        private void updateSafeSpawnDestinations(
                ExtractLevelRenderStateEvent event,
                Vec3 camera,
                double visualRangeSquared
        ) {
            if (this.staticField == null || this.agentCount >= this.targetAgentCount) {
                this.safeSpawnDestinationCount = 0;
                this.safeSpawnDestinationIds = new int[0];
                return;
            }
            int[] destinations = this.staticField.destinationPixels();
            int[] safeDestinationIds = new int[destinations.length];
            int safeCount = 0;
            Vec3 anchor = this.staticField.worldAnchor();
            double originX = anchor.x - SnowtownGpuCrowdStaticField.HALF_SIZE;
            double originZ = anchor.z - SnowtownGpuCrowdStaticField.HALF_SIZE;
            for (int destination = 0; destination < destinations.length; destination++) {
                int encoded = destinations[destination];
                int localX = encoded & 255;
                int localZ = encoded >>> 8 & 255;
                double worldX = originX + localX + 0.5D;
                double worldY = this.staticField.surfaceHeight(localX, localZ);
                double worldZ = originZ + localZ + 0.5D;
                AABB spawnBounds = new AABB(
                        worldX - SPAWN_VISIBILITY_MARGIN,
                        worldY - 1.0D,
                        worldZ - SPAWN_VISIBILITY_MARGIN,
                        worldX + SPAWN_VISIBILITY_MARGIN,
                        worldY + 4.0D,
                        worldZ + SPAWN_VISIBILITY_MARGIN
                );
                boolean visible = event.getFrustum().isVisible(spawnBounds)
                        && horizontalDistanceToBoundsSquared(camera, spawnBounds)
                        <= visualRangeSquared;
                if (!visible) {
                    safeDestinationIds[safeCount++] = destination;
                }
            }
            this.safeSpawnDestinationCount = safeCount;
            this.safeSpawnDestinationIds = Arrays.copyOf(safeDestinationIds, safeCount);
        }

        private int prepareSpawnDestinations(int firstAgent, int requestedBirths) {
            int[] eligible = new int[this.safeSpawnDestinationIds.length];
            int eligibleCount = 0;
            for (int destination : this.safeSpawnDestinationIds) {
                if (clientTick - this.lastSpawnTickByDestination[destination]
                        >= SPAWN_DESTINATION_REUSE_TICKS) {
                    eligible[eligibleCount++] = destination;
                }
            }
            int births = Math.min(requestedBirths, eligibleCount);
            if (births <= 0) {
                return 0;
            }

            int[] destinations = this.staticField.destinationPixels();
            this.spawnDestinationPixels = new int[destinations.length];
            for (int slot = 0; slot < destinations.length; slot++) {
                this.spawnDestinationPixels[slot] = destinations[slot] & 0xFFFF;
            }
            int rotation = Math.floorMod(this.spawnGeneration * 31, eligibleCount);
            for (int offset = 0; offset < births; offset++) {
                int eligibleOrdinal = (rotation
                        + (int)((long)offset * eligibleCount / births)) % eligibleCount;
                int destination = eligible[eligibleOrdinal];
                int agent = firstAgent + offset;
                int spawnSlot = Math.floorMod(
                        agent * 73,
                        SnowtownGpuCrowdStaticField.SPAWN_POINT_COUNT
                );
                this.spawnDestinationPixels[spawnSlot] = destinations[destination] & 0xFFFF;
                this.lastSpawnTickByDestination[destination] = clientTick;
            }
            this.spawnGeneration++;
            return births;
        }

        private void installIdentitySpawnDestinations() {
            int[] destinations = this.staticField.destinationPixels();
            this.spawnDestinationPixels = new int[destinations.length];
            for (int slot = 0; slot < destinations.length; slot++) {
                this.spawnDestinationPixels[slot] = destinations[slot] & 0xFFFF;
            }
            this.spawnGeneration++;
        }

        private void releaseHeavyState() {
            this.staticField = null;
            this.safeSpawnDestinationCount = 0;
            this.safeSpawnDestinationIds = new int[0];
            this.spawnDestinationPixels = new int[0];
            this.dirtyLightRegion = null;
            this.lightRefreshAfterTick = Long.MAX_VALUE;
            this.appliedLightRevision = this.lightRevision;
        }

        private double centerDistanceSquared(Vec3 position) {
            double centerX = (double)this.key.sectorX()
                    * SnowtownGpuCrowdStaticField.SIZE
                    + SnowtownGpuCrowdStaticField.HALF_SIZE;
            double centerZ = (double)this.key.sectorZ()
                    * SnowtownGpuCrowdStaticField.SIZE
                    + SnowtownGpuCrowdStaticField.HALF_SIZE;
            double deltaX = centerX - position.x;
            double deltaZ = centerZ - position.z;
            return deltaX * deltaX + deltaZ * deltaZ;
        }

        private boolean isKnownOffscreen() {
            return this.visibilityKnown && !this.onScreen;
        }

        private boolean hasSafeBirthLocations() {
            return this.visibilityKnown
                    && this.safeSpawnDestinationCount >= MIN_SAFE_SPAWN_DESTINATIONS
                    && availableSpawnDestinationCount() > 0;
        }

        private int availableSpawnDestinationCount() {
            int available = 0;
            for (int destination : this.safeSpawnDestinationIds) {
                if (clientTick - this.lastSpawnTickByDestination[destination]
                        >= SPAWN_DESTINATION_REUSE_TICKS) {
                    available++;
                }
            }
            return available;
        }
    }

    private record SurfaceDemand(
            int seedX,
            int seedY,
            int seedZ,
            int outdoorPopulation,
            long[] planningMaskWords
    ) {
        private static SurfaceDemand from(SurfacePopulation surface) {
            long[] planningMaskWords = new long[surface.planningMaskWords().size()];
            for (int word = 0; word < planningMaskWords.length; word++) {
                planningMaskWords[word] = surface.planningMaskWords().get(word);
            }
            return new SurfaceDemand(
                    surface.seedX(),
                    surface.seedY(),
                    surface.seedZ(),
                    surface.outdoorPopulation(),
                    planningMaskWords
            );
        }
    }

    private record SectorCandidate(
            SectorKey key,
            SurfaceDemand demand,
            double distanceSquared
    ) {
    }

    private record SectorBuildResult(
            int serial,
            SectorKey key,
            boolean rebuild,
            int blockRevision,
            SnowtownGpuCrowdStaticField field
    ) {
    }
}
