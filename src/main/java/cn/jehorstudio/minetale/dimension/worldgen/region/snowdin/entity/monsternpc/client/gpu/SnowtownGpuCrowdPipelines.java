package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.util.List;

// 集中注册 GPU 人群模拟、实体顶点物化与交互拾取管线。
@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class SnowtownGpuCrowdPipelines {
    public static final RenderPipeline BUILD_OCCUPANCY = RenderPipeline.builder()
            .withLocation(id("pipeline/monster_npc/gpu_crowd_occupancy"))
            .withVertexShader(id("core/monster_npc/gpu_crowd_occupancy"))
            .withFragmentShader(id("core/monster_npc/gpu_crowd_occupancy_first"))
            .withSampler("PositionState")
            .withUniform("CrowdSimulation", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withDepthWrite(true)
            .withCull(false)
            .build();

    public static final RenderPipeline UPDATE_DENSITY = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(id("pipeline/monster_npc/gpu_crowd_density"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/monster_npc/gpu_crowd_density"))
            .withSampler("PositionState")
            .withSampler("StaticField")
            .withSampler("OccupancyAtlas")
            .withUniform("CrowdSimulation", UniformType.UNIFORM_BUFFER)
            .withCull(false)
            .build();

    public static final RenderPipeline UPDATE_BEHAVIOR = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(id("pipeline/monster_npc/gpu_crowd_behavior"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/monster_npc/gpu_crowd_behavior"))
            .withSampler("PositionState")
            .withSampler("VelocityState")
            .withSampler("BehaviorState")
            .withSampler("StaticField")
            .withSampler("AppearanceData")
            .withUniform("CrowdSimulation", UniformType.UNIFORM_BUFFER)
            .withCull(false)
            .build();

    public static final RenderPipeline UPDATE_VELOCITY_REFERENCE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(id("pipeline/monster_npc/gpu_crowd_velocity_reference"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/monster_npc/gpu_crowd_velocity_reference"))
            .withSampler("PositionState")
            .withSampler("VelocityState")
            .withSampler("StaticField")
            .withSampler("FlowField")
            .withSampler("DensityField")
            .withSampler("BehaviorState")
            .withSampler("AppearanceData")
            .withSampler("OccupancyAtlas")
            .withUniform("CrowdSimulation", UniformType.UNIFORM_BUFFER)
            .withCull(false)
            .build();

    public static final RenderPipeline UPDATE_POSITION = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(id("pipeline/monster_npc/gpu_crowd_position"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/monster_npc/gpu_crowd_update"))
            .withSampler("PositionState")
            .withSampler("VelocityState")
            .withSampler("BehaviorState")
            .withSampler("StaticField")
            .withSampler("DestinationData")
            .withSampler("AppearanceData")
            .withSampler("OccupancyAtlas")
            .withUniform("CrowdSimulation", UniformType.UNIFORM_BUFFER)
            .withCull(false)
            .build();

    // 将模拟状态编码为 NEW_ENTITY 或 Iris ENTITY 对应的原始顶点布局。
    public static final RenderPipeline BAKE_ENTITY_VERTICES = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(id("pipeline/monster_npc/gpu_crowd_bake_entity_vertices"))
            .withVertexShader(id("core/battle/post/fullscreen"))
            .withFragmentShader(id("core/monster_npc/gpu_crowd_bake_entity_vertices"))
            .withSampler("PositionStateA")
            .withSampler("PositionStateB")
            .withSampler("VelocityStateA")
            .withSampler("VelocityStateB")
            .withSampler("BehaviorStateA")
            .withSampler("BehaviorStateB")
            .withSampler("MacroStaticFields")
            .withSampler("MacroLightFields")
            .withSampler("RenderAgentDirectory")
            .withSampler("AnimationFrames")
            .withSampler("StaticGeometry")
            .withUniform("CrowdMacroPages", UniformType.UNIFORM_BUFFER)
            .withUniform("CrowdVertexBake", UniformType.UNIFORM_BUFFER)
            .withUniform("CrowdAppearanceAtlas", UniformType.UNIFORM_BUFFER)
            .withCull(false)
            .build();

    // 拾取 pass 只写准星像素对应的切片、agent ID 与同帧局部位置。
    public static final RenderPipeline PICK_AGENTS = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(id("pipeline/monster_npc/gpu_crowd_pick"))
            .withVertexShader(id("core/monster_npc/gpu_crowd_pick"))
            .withFragmentShader(id("core/monster_npc/gpu_crowd_pick"))
            .withSampler("PreviousPositionState")
            .withSampler("CurrentPositionState")
            .withSampler("PreviousVelocityState")
            .withSampler("CurrentVelocityState")
            .withSampler("PreviousBehaviorState")
            .withSampler("CurrentBehaviorState")
            .withSampler("StaticField")
            .withSampler("LightField")
            .withSampler("AnimationFrames")
            .withUniform("CrowdInteraction", UniformType.UNIFORM_BUFFER)
            .withUniform("CrowdAppearance", UniformType.UNIFORM_BUFFER)
            .withShaderDefine("NO_OVERLAY")
            .withShaderDefine("PER_FACE_LIGHTING")
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(true)
            .withCull(false)
            .build();

    private SnowtownGpuCrowdPipelines() {
    }

    /* 过去直接注册为静态渲染管线，编译出错会直接炸掉整个游戏*/
    /* See: Issue#1 */
//    @SubscribeEvent
//    public static void register(RegisterRenderPipelinesEvent event) {
//        event.registerPipeline(BUILD_OCCUPANCY);
//        event.registerPipeline(UPDATE_DENSITY);
//        event.registerPipeline(UPDATE_BEHAVIOR);
//        event.registerPipeline(UPDATE_VELOCITY_REFERENCE);
//        event.registerPipeline(UPDATE_POSITION);
//        event.registerPipeline(BAKE_ENTITY_VERTICES);
//        event.registerPipeline(PICK_AGENTS);
//    }
    public static final List<RenderPipeline> ALL = List.of(
            BUILD_OCCUPANCY,
            UPDATE_DENSITY,
            UPDATE_BEHAVIOR,
            UPDATE_VELOCITY_REFERENCE,
            UPDATE_POSITION,
            BAKE_ENTITY_VERTICES,
            PICK_AGENTS
    );

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, path);
    }
}
