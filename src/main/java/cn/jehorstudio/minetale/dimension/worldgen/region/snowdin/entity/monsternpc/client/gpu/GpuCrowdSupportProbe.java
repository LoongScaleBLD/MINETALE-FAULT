package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;

public final class GpuCrowdSupportProbe {
    private static boolean available;
    public static boolean isAvailable(){
        return available;
    }

    public static void probe(){
        available=false;

        var device= RenderSystem.getDevice();

        for (RenderPipeline pipeline:SnowtownGpuCrowdPipelines.ALL){
            try {
                var compiled = device.precompilePipeline(pipeline);
                if (!compiled.isValid()){
                    MineTale.LOGGER.warn(
                            "GPU NPC 已禁用：渲染管线编译失败 {}; GPU= {} / {} / {}",
                            pipeline.getLocation(),
                            device.getVendor(),
                            device.getRenderer(),
                            device.getVersion()
                    );
                    return;
                }
            } catch (RuntimeException | LinkageError e){
                MineTale.LOGGER.warn(
                        "GPU NPC 已禁用：设备检查异常; GPU={} / {} / {}",
                        device.getVendor(),
                        device.getRenderer(),
                        device.getVersion(),
                        e
                );
                return;
            }
        }

        available=true;
        MineTale.LOGGER.info(
                "GPU NPC 正常启用"
        );
    }
}
