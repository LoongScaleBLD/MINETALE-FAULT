#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

uniform sampler2D PreviousPositionState;
uniform sampler2D CurrentPositionState;
uniform sampler2D PreviousVelocityState;
uniform sampler2D CurrentVelocityState;
uniform sampler2D PreviousBehaviorState;
uniform sampler2D CurrentBehaviorState;
uniform sampler2D StaticField;
uniform sampler2D LightField;
uniform sampler2D AnimationFrames;
uniform sampler2D Sampler2;

layout(std140) uniform CrowdInteraction {
    vec4 CrowdAnchor; // xyz=相机相对锚点，w=插值系数
    vec4 DrawParams;  // elapsedSeconds、agentCount、textureSize、extentX
    vec4 DrawLayout;  // extentZ、velocityReference、保留、candidateToken
    vec4 FieldParams; // textureSize、heightRange、maxClearance、保留
    vec4 StatePage;   // xy=共享状态 Atlas 原点
};

layout(std140) uniform CrowdAppearance {
    vec4 AppearanceLayout; // appearanceId、appearanceCount、idleFrames、walkFrames
    vec4 AppearanceMotion; // nominalSpeed、positionRange、idleSeconds、walkSeconds
};

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 pickData;
out vec3 interactionRelativePosition;
out float interactionReach;

const int HEADING_LEVELS = 2048;
const float TAU = 6.2831853;
const float MAX_LOOK_YAW = 0.7330383;
const float HEIGHT_TRANSITION_BAND = 0.14;

float decode16(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0;
}

float hash11(float value) {
    return fract(sin(value * 91.3458 + 17.23) * 47453.5453);
}

int decodeBytes(vec2 encoded) {
    ivec2 bytes = ivec2(floor(encoded * 255.0 + 0.5));
    return bytes.x * 256 + bytes.y;
}

float decodeHeading(sampler2D stateTexture, ivec2 texel) {
    vec4 encoded = texelFetch(stateTexture, texel, 0);
    int packedState = decodeBytes(encoded.rg);
    int packedTimerHeading = decodeBytes(encoded.ba);
    int lowBits = (packedState >> 11) & 31;
    int highBits = (packedTimerHeading >> 10) & 63;
    int heading = lowBits | (highBits << 5);
    return float(heading) / float(HEADING_LEVELS) * TAU;
}

vec2 decodePosition(sampler2D stateTexture, ivec2 texel) {
    vec4 encoded = texelFetch(stateTexture, texel, 0);
    return vec2(
            decode16(encoded.rg) * DrawParams.w * 2.0 - DrawParams.w,
            decode16(encoded.ba) * DrawLayout.x * 2.0 - DrawLayout.x
    );
}

vec2 decodeVelocity(sampler2D stateTexture, ivec2 texel) {
    float velocityRange = DrawLayout.y * 1.25;
    vec4 encoded = texelFetch(stateTexture, texel, 0);
    return vec2(
            (decode16(encoded.rg) * 2.0 - 1.0) * velocityRange,
            (decode16(encoded.ba) * 2.0 - 1.0) * velocityRange
    );
}

ivec2 fieldTexel(vec2 position) {
    vec2 extent = vec2(DrawParams.w, DrawLayout.x);
    vec2 normalized = position / (extent * 2.0) + 0.5;
    int fieldSize = int(FieldParams.x + 0.5);
    return clamp(ivec2(floor(normalized * FieldParams.x)), ivec2(0), ivec2(fieldSize - 1));
}

float heightAt(ivec2 texel) {
    vec4 fieldSample = texelFetch(StaticField, texel, 0);
    return (decode16(fieldSample.gb) * 2.0 - 1.0) * FieldParams.y;
}

float continuousHeight(vec2 position) {
    int fieldSize = int(FieldParams.x + 0.5);
    ivec2 fieldMaximum = ivec2(fieldSize - 1);
    vec2 extent = vec2(DrawParams.w, DrawLayout.x);
    vec2 gridPosition = position + extent;
    ivec2 center = ivec2(floor(gridPosition));
    vec2 local = fract(gridPosition);

    // 平台内部保持恒高，在边缘窄带两侧各用半段 smoothstep 连续升降。
    ivec2 low = center;
    ivec2 high = center;
    vec2 blend = vec2(0.0);
    if (local.x < HEIGHT_TRANSITION_BAND) {
        low.x -= 1;
        blend.x = 0.5 + 0.5 * smoothstep(
                0.0, HEIGHT_TRANSITION_BAND, local.x);
    } else if (local.x > 1.0 - HEIGHT_TRANSITION_BAND) {
        high.x += 1;
        blend.x = 0.5 * smoothstep(
                1.0 - HEIGHT_TRANSITION_BAND, 1.0, local.x);
    }
    if (local.y < HEIGHT_TRANSITION_BAND) {
        low.y -= 1;
        blend.y = 0.5 + 0.5 * smoothstep(
                0.0, HEIGHT_TRANSITION_BAND, local.y);
    } else if (local.y > 1.0 - HEIGHT_TRANSITION_BAND) {
        high.y += 1;
        blend.y = 0.5 * smoothstep(
                1.0 - HEIGHT_TRANSITION_BAND, 1.0, local.y);
    }
    ivec2 cells[4] = ivec2[4](
            clamp(low, ivec2(0), fieldMaximum),
            clamp(ivec2(high.x, low.y), ivec2(0), fieldMaximum),
            clamp(ivec2(low.x, high.y), ivec2(0), fieldMaximum),
            clamp(high, ivec2(0), fieldMaximum));
    float weights[4] = float[4](
            (1.0 - blend.x) * (1.0 - blend.y),
            blend.x * (1.0 - blend.y),
            (1.0 - blend.x) * blend.y,
            blend.x * blend.y);
    float weightedHeight = 0.0;
    float totalWeight = 0.0;
    for (int sampleIndex = 0; sampleIndex < 4; sampleIndex++) {
        float walkable = texelFetch(StaticField, cells[sampleIndex], 0).r > 0.5 ? 1.0 : 0.0;
        float weight = weights[sampleIndex] * walkable;
        weightedHeight += heightAt(cells[sampleIndex]) * weight;
        totalWeight += weight;
    }
    return totalWeight > 0.0001
            ? weightedHeight / totalWeight
            : heightAt(fieldTexel(position));
}

vec3 decodeAnimationPosition(int vertexId, int row, int lookPose) {
    int column = vertexId * 6 + lookPose * 2;
    vec4 encodedXY = texelFetch(AnimationFrames, ivec2(column, row), 0);
    vec4 encodedZ = texelFetch(AnimationFrames, ivec2(column + 1, row), 0);
    float range = AppearanceMotion.y;
    return vec3(
            (decode16(encodedXY.rg) * 2.0 - 1.0) * range,
            (decode16(encodedXY.ba) * 2.0 - 1.0) * range,
            (decode16(encodedZ.rg) * 2.0 - 1.0) * range);
}

vec3 animationFrame(
        int vertexId,
        int rowOffset,
        int frameCount,
        float phase,
        int lookPose
) {
    float frame = fract(phase) * float(frameCount);
    int frame0 = int(floor(frame)) % frameCount;
    int frame1 = (frame0 + 1) % frameCount;
    return mix(
            decodeAnimationPosition(vertexId, rowOffset + frame0, lookPose),
            decodeAnimationPosition(vertexId, rowOffset + frame1, lookPose),
            fract(frame));
}

float gazeTarget(int agentId, float segment) {
    float gazeActivation = hash11(float(agentId) * 13.0 + segment * 61.0 + 701.0);
    if (gazeActivation < 0.38) {
        return 0.0;
    }
    float raw = hash11(float(agentId) * 29.0 + segment * 43.0 + 733.0) * 2.0 - 1.0;
    return sign(raw) * pow(abs(raw), 1.45);
}

float ambientGaze(int agentId, float speed, float nominalSpeed) {
    float rate = mix(0.115, 0.175, hash11(float(agentId) + 757.0));
    float time = DrawParams.x * rate + hash11(float(agentId) + 769.0) * 23.0;
    float segment = floor(time);
    // 注视点在时间段内保持稳定，仅在段尾平滑切换。
    float transition = smoothstep(0.80, 1.0, fract(time));
    float target = mix(
            gazeTarget(agentId, segment),
            gazeTarget(agentId, segment + 1.0),
            transition);
    float walking = smoothstep(nominalSpeed * 0.10, nominalSpeed * 0.55, speed);
    return target * MAX_LOOK_YAW * mix(0.70, 0.34, walking);
}

void main() {
    int appearanceId = int(AppearanceLayout.x + 0.5);
    int appearanceCount = int(AppearanceLayout.y + 0.5);
    int agentId = appearanceId + gl_InstanceID * appearanceCount;
    int stateSize = int(DrawParams.z + 0.5);
    ivec2 texel = ivec2(StatePage.xy + 0.5)
            + ivec2(agentId % stateSize, agentId / stateSize);

    bool newlyActivated = all(equal(
            texelFetch(PreviousPositionState, texel, 0),
            vec4(0.0)));
    vec2 previousPosition = decodePosition(PreviousPositionState, texel);
    vec2 currentPosition = decodePosition(CurrentPositionState, texel);
    if (newlyActivated) {
        previousPosition = currentPosition;
    }
    vec2 agentPosition = mix(previousPosition, currentPosition, CrowdAnchor.w);
    float heightOffset = continuousHeight(agentPosition);

    vec2 previousVelocity = decodeVelocity(PreviousVelocityState, texel);
    vec2 currentVelocity = decodeVelocity(CurrentVelocityState, texel);
    if (newlyActivated) {
        previousVelocity = currentVelocity;
    }
    vec2 velocity = mix(previousVelocity, currentVelocity, CrowdAnchor.w);
    float agentSpeed = length(velocity);

    float previousFacing = decodeHeading(PreviousBehaviorState, texel);
    float currentFacing = decodeHeading(CurrentBehaviorState, texel);
    if (newlyActivated) {
        previousFacing = currentFacing;
    }
    float facingDelta = atan(
            sin(currentFacing - previousFacing),
            cos(currentFacing - previousFacing));
    float facingAngle = previousFacing + facingDelta * CrowdAnchor.w;
    vec2 forward = vec2(cos(facingAngle), sin(facingAngle));
    vec2 right = vec2(-forward.y, forward.x);

    int idleFrames = max(int(AppearanceLayout.z + 0.5), 1);
    int walkFrames = max(int(AppearanceLayout.w + 0.5), 1);
    float phaseOffset = hash11(float(agentId) + 101.0);
    vec3 idlePosition = animationFrame(
            gl_VertexID,
            0,
            idleFrames,
            DrawParams.x / max(AppearanceMotion.z, 0.01) + phaseOffset,
            0);
    vec3 walkPosition = animationFrame(
            gl_VertexID,
            idleFrames,
            walkFrames,
            DrawParams.x / max(AppearanceMotion.w, 0.01) + phaseOffset,
            0);
    // 极低速保持 idle，接近该外观半速后才完整混入 walking。
    float nominalSpeed = max(AppearanceMotion.x, 0.05);
    float walkingWeight = smoothstep(nominalSpeed * 0.12, nominalSpeed * 0.48, agentSpeed);
    vec3 local = mix(idlePosition, walkPosition, walkingWeight);

    float intendedFacing = agentSpeed > nominalSpeed * 0.08
            ? atan(velocity.y, velocity.x)
            : facingAngle;
    float turnLead = atan(
            sin(intendedFacing - facingAngle),
            cos(intendedFacing - facingAngle));
    float ambient = ambientGaze(agentId, agentSpeed, nominalSpeed);
    float turnDominance = smoothstep(0.16, 0.55, abs(turnLead));
    float lookYaw = clamp(
            turnLead * 0.88 + ambient * (1.0 - turnDominance),
            -MAX_LOOK_YAW,
            MAX_LOOK_YAW);
    if (Color.r > 0.5) {
        int lookPose = lookYaw >= 0.0 ? 1 : 2;
        vec3 idleLook = animationFrame(
                gl_VertexID,
                0,
                idleFrames,
                DrawParams.x / max(AppearanceMotion.z, 0.01) + phaseOffset,
                lookPose);
        vec3 walkLook = animationFrame(
                gl_VertexID,
                idleFrames,
                walkFrames,
                DrawParams.x / max(AppearanceMotion.w, 0.01) + phaseOffset,
                lookPose);
        vec3 lookedLocal = mix(idleLook, walkLook, walkingWeight);
        local = mix(local, lookedLocal, abs(lookYaw) / MAX_LOOK_YAW);
    }

    // GeckoLib 本地 -Z 为前方，直接旋转到模拟持久朝向。
    vec2 facingHorizontal = right * local.x - forward * local.z;
    vec3 facingLocal = vec3(facingHorizontal.x, local.y, facingHorizontal.y);
    vec3 relativeWorld = CrowdAnchor.xyz
            + vec3(agentPosition.x, heightOffset, agentPosition.y)
            + facingLocal;

    vec3 localNormal = normalize(Normal);
    if (Color.r > 0.5) {
        float lookCos = cos(lookYaw);
        float lookSin = sin(lookYaw);
        localNormal.xz = mat2(lookCos, lookSin, -lookSin, lookCos) * localNormal.xz;
    }
    vec2 normalHorizontal = right * localNormal.x - forward * localNormal.z;
    vec3 facingNormal = normalize(vec3(normalHorizontal.x, localNormal.y, normalHorizontal.y));
    float tint = mix(0.88, 1.04, hash11(float(agentId) + 4.0));
    vec4 baseColor = vec4(vec3(tint), 1.0);
    vec2 directionalLight = minecraft_compute_light(
            Light0_Direction,
            Light1_Direction,
            facingNormal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-directionalLight, baseColor);
    vertexPerFaceColorFront = minecraft_mix_light_separate(directionalLight, baseColor);
    vec2 encodedLight = texelFetch(LightField, fieldTexel(agentPosition), 0).rg;
    ivec2 lightTexel = ivec2(floor(encodedLight * 15.0 + 0.5));
    lightMapColor = texelFetch(Sampler2, lightTexel, 0);
    overlayColor = vec4(0.0);
    texCoord0 = UV0;
    sphericalVertexDistance = fog_spherical_distance(relativeWorld);
    cylindricalVertexDistance = fog_cylindrical_distance(relativeWorld);
    interactionRelativePosition = relativeWorld + ModelOffset;
    interactionReach = FieldParams.w;
    pickData = vec4(0.0);
    if (DrawLayout.w > 0.5) {
        // 一个 RGBA8 像素封存切片、agent ID 与同帧插值位置，避免异步回读错配新状态。
        int candidateIndex = int(DrawLayout.w + 0.5) - 1;
        int encodedAgent = agentId + 1;
        if (candidateIndex < 0 || candidateIndex > 15 || encodedAgent > 4095) {
            interactionReach = 0.0;
        } else {
            int packedIdentity = (candidateIndex << 12) | encodedAgent;
            vec2 normalizedPosition = clamp(
                    agentPosition / (vec2(DrawParams.w, DrawLayout.x) * 2.0) + 0.5,
                    vec2(0.0),
                    vec2(1.0));
            pickData = vec4(
                    float(packedIdentity & 255) / 255.0,
                    float((packedIdentity >> 8) & 255) / 255.0,
                    normalizedPosition.x,
                    normalizedPosition.y);
        }
    }
    gl_Position = ProjMat * ModelViewMat * vec4(interactionRelativePosition, 1.0);
}
