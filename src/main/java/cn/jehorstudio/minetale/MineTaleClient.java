package cn.jehorstudio.minetale;

import cn.jehorstudio.minetale.battle.network.client.BattleClientNetworkHandlers;
import cn.jehorstudio.minetale.battle.presentation.BattlePresentation;
import cn.jehorstudio.minetale.battle.presentation.BattlePreparation;
import cn.jehorstudio.minetale.battle.presentation.BattleKeyMappings;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureService;
import cn.jehorstudio.minetale.battle.presentation.screen.BattleScriptSelectionScreen;
import cn.jehorstudio.minetale.battle.presentation.screen.render.MaterialTextures;
import cn.jehorstudio.minetale.battle.script.BattleScriptIds;
import cn.jehorstudio.minetale.battle.script.BattleScriptReloadListener;
import cn.jehorstudio.minetale.configuration.client.MineTaleConfigurationScreen;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.GpuCrowdSupportProbe;
import cn.jehorstudio.minetale.narrative.client.DialogueClientNetworkHandlers;
import cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.client.MysteriousCampfireSmokeParticle;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.TransitionClientNetworkHandlers;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.particle.BarrierVortexParticleAction;
import cn.jehorstudio.minetale.content.entity.flowey.FloweyRenderer;
import cn.jehorstudio.minetale.content.entity.flowey.FloweyRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu.SnowtownCrowdClient;
import cn.jehorstudio.minetale.content.player.soul.SoulRegistry;
import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import cn.jehorstudio.minetale.lib.ObjLoader;
import cn.jehorstudio.minetale.lib.client.particle.gpu.GpuParticleSystem;
import cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.MysteriousCampfireRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.fog.environment.DimensionOrBossFogEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.resources.VanillaClientListeners;

import java.util.concurrent.CompletableFuture;

@Mod(value = MineTale.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(
        modid = MineTale.MODID,
        value = Dist.CLIENT
)
public class MineTaleClient {
    private static final int SNOWDIN_SILVER_BLUE_LEAVES = 0xB8D4E6;
    private static final ResourceKey<Biome> SNOWDIN_BIOME = ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "snowdin")
    );

    public MineTaleClient(IEventBus modEventBus, ModContainer container) {
        cn.jehorstudio.minetale.magic.MagicClient.register(modEventBus);
        GpuParticleSystem.register(BarrierVortexParticleAction.INSTANCE);
        modEventBus.addListener(BattleClientNetworkHandlers::register);
        modEventBus.addListener(DialogueClientNetworkHandlers::register);
        modEventBus.addListener(SnowtownCrowdClient::registerPayloadHandlers);
        modEventBus.addListener(TransitionClientNetworkHandlers::register);
        container.registerExtensionPoint(IConfigScreenFactory.class, MineTaleConfigurationScreen::new);
    }

    @SubscribeEvent
    public static void registerBlockColorHandlers(RegisterColorHandlersEvent.Block event) {
        event.register(MineTaleClient::snowtownSpruceLeavesColor, CommonBlocksRegistry.SNOWTOWN_SPRUCE_LEAVES.get());
    }

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(TemplateMarkerRegistry.TEMPLATE_MARKER_MENU.get(), TemplateMarkerScreen::new);
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FloweyRegistry.FLOWEY.get(), FloweyRenderer::new);
        // SoulEntity 只拥有交互代理与动作事实，可见 Transform 统一由动作控制器提交。
        event.registerEntityRenderer(SoulRegistry.SOUL.get(), NoopRenderer::new);
    }

    @SubscribeEvent
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(
                MysteriousCampfireRegistry.COSY_SMOKE.get(),
                MysteriousCampfireSmokeParticle.CosyProvider::new
        );
        event.registerSpriteSet(
                MysteriousCampfireRegistry.SIGNAL_SMOKE.get(),
                MysteriousCampfireSmokeParticle.SignalProvider::new
        );
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        EnvironmentCaptureService.INSTANCE.tick(minecraft);
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        BattleClientNetworkHandlers.startPendingBattleIfReady(minecraft);
        BattlePreparation.INSTANCE.tick(minecraft);
        while (BattleKeyMappings.START_BATTLE_INSTANCE.consumeClick()) {
            if (BattlePresentation.active() == null) {
                minecraft.setScreen(new BattleScriptSelectionScreen(minecraft.screen));
            }
        }

        BattlePresentation active = BattlePresentation.active();
        if (active != null) {
            active.onClientTick();
        }
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        EnvironmentCaptureService.INSTANCE.applyCameraAngles(event);
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || !(event.getEnvironment() instanceof DimensionOrBossFogEnvironment)
                || !minecraft.level.getBiome(event.getCamera().getBlockPosition()).is(SNOWDIN_BIOME)) {
            return;
        }

        event.setNearPlaneDistance(event.getFogData().renderDistanceStart);
        event.setFarPlaneDistance(event.getFogData().renderDistanceEnd);
    }

    @SubscribeEvent
    public static void onExtractBlockOutline(ExtractBlockOutlineRenderStateEvent event) {
        if (EnvironmentCaptureService.INSTANCE.suppressTransientWorldEffects()) {
            event.setCanceled(true);
        }
    }

    private static int snowtownSpruceLeavesColor(BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
        return 0xFF000000 | SNOWDIN_SILVER_BLUE_LEAVES;
    }

    @EventBusSubscriber(
            modid = MineTale.MODID,
            value = Dist.CLIENT
    )
    public static final class MineTaleClientReloads {
        @SubscribeEvent
        public static void addClientReloadListeners(AddClientReloadListenersEvent event) {
            event.addListener(BattleScriptIds.RELOAD_LISTENER, BattleScriptReloadListener.create());
            event.addListener(
                    ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "obj_models"),
                    ObjLoader.INSTANCE
            );
            event.addListener(
                    ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "battle_emissive_materials"),
                    MaterialTextures.INSTANCE
            );
            event.addListener(
                    ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "battle_environment_capture"),
                    EnvironmentCaptureService.INSTANCE
            );
            event.addListener(
                    ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "snowtown_gpu_crowd"),
                    (sharedState, prepareExecutor, barrier, applyExecutor) ->
                            CompletableFuture.completedFuture(Boolean.TRUE)
                                    .thenCompose(barrier::wait)
                                    .thenRunAsync(()-> {
                                        SnowtownCrowdClient.invalidateRenderResources();
                                        GpuCrowdSupportProbe.probe();
                                    }, applyExecutor)
            );
            event.addDependency(
                    VanillaClientListeners.SHADERS,
                    ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "snowtown_gpu_crowd")
            );
        }
    }
}
