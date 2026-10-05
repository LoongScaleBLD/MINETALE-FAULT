#version 330

uniform sampler2D PositionStateA;
uniform sampler2D PositionStateB;
uniform sampler2D VelocityStateA;
uniform sampler2D VelocityStateB;
uniform sampler2D BehaviorStateA;
uniform sampler2D BehaviorStateB;
uniform sampler2D MacroStaticFields;
uniform sampler2D MacroLightFields;
uniform sampler2D RenderAgentDirectory;
uniform sampler2D AnimationFrames;
uniform sampler2D StaticGeometry;

const int APPEARANCE_COUNT = 28;
const int MAX_PAGES = 64;

layout(std140) uniform CrowdMacroPages {
    vec4 MacroLayout; // x/z=extent，z=velocityReference，w=directoryWidth
    vec4 MacroField;  // x=fieldSize，y=heightRange，z=maxClearance，w=pageColumns
    vec4 PageAnchors[MAX_PAGES]; // xyz=相机相对锚点，w=插值系数
    vec4 PageParams[MAX_PAGES];  // x=elapsed，y=stateSize，z=currentIsA，w=previousAvailable
};

layout(std140) uniform CrowdVertexBake {
    vec4 BakeTarget; // x=atlasWidth，y=rowStart，z=vertexStride，w=layoutKind
    vec4 BakeCounts; // x=expandedVertexCount，其余保留
    vec4 BatchRanges[APPEARANCE_COUNT]; // x=firstVertex，y=exclusiveEndVertex，z=directoryStart
};

layout(std140) uniform CrowdAppearanceAtlas {
    vec4 AppearanceLayouts[APPEARANCE_COUNT]; // appearanceId、数量、idleFrames、walkFrames
    vec4 AppearanceMotions[APPEARANCE_COUNT]; // nominalSpeed、positionRange、idleSeconds、walkSeconds
    vec4 GeometryRegions[APPEARANCE_COUNT]; // texel 空间的 x、y、width、height
    vec4 AnimationRegions[APPEARANCE_COUNT]; // texel 空间的 x、y、width、height
    vec4 DiffuseRegions[APPEARANCE_COUNT]; // 归一化 x、y、width、height
};

out vec4 fragColor;

vec4 CrowdAnchor;
vec4 DrawParams;
vec4 DrawLayout;
vec4 FieldParams;
ivec2 CurrentFieldOrigin;

const int HEADING_LEVELS = 2048;
const float TAU = 6.2831853;
const float MAX_LOOK_YAW = 0.7330383;
const float SIMULATION_STEP_SECONDS = 0.1;
const float HEIGHT_TRANSITION_BAND = 0.14;
const int ATTRIBUTE_POSITION = 1;
const int ATTRIBUTE_COLOR = 2;
const int ATTRIBUTE_UV = 4;
const int ATTRIBUTE_OVERLAY = 8;
const int ATTRIBUTE_LIGHT = 16;
const int ATTRIBUTE_NORMAL = 32;
const int ATTRIBUTE_MIDDLE_UV = 64;
const int ATTRIBUTE_TANGENT = 128;
const int APPEARANCE_ATTRIBUTES = ATTRIBUTE_POSITION
        | ATTRIBUTE_COLOR
        | ATTRIBUTE_UV
        | ATTRIBUTE_LIGHT
        | ATTRIBUTE_NORMAL
        | ATTRIBUTE_MIDDLE_UV
        | ATTRIBUTE_TANGENT;

struct ExpandedVertex {
    vec3 position;
    vec4 color;
    vec2 uv;
    uvec2 overlay;
    uvec2 light;
    vec3 normal;
    vec2 middleUv;
    vec4 tangent;
};

float decode16(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0;
}

int decodeBytes(vec2 encoded) {
    ivec2 bytes = ivec2(floor(encoded * 255.0 + 0.5));
    return bytes.x * 256 + bytes.y;
}

float hash11(float value) {
    return fract(sin(value * 91.3458 + 17.23) * 47453.5453);
}

float decodeHeading(vec4 encoded) {
    int packedState = decodeBytes(encoded.rg);
    int packedTimerHeading = decodeBytes(encoded.ba);
    int lowBits = (packedState >> 11) & 31;
    int highBits = (packedTimerHeading >> 10) & 63;
    int heading = lowBits | (highBits << 5);
    return float(heading) / float(HEADING_LEVELS) * TAU;
}

vec2 decodePosition(vec4 encoded) {
    return vec2(
            decode16(encoded.rg) * DrawParams.w * 2.0 - DrawParams.w,
            decode16(encoded.ba) * DrawLayout.x * 2.0 - DrawLayout.x);
}

vec2 decodeVelocity(vec4 encoded) {
    float velocityRange = DrawLayout.y * 1.25;
    return vec2(
            (decode16(encoded.rg) * 2.0 - 1.0) * velocityRange,
            (decode16(encoded.ba) * 2.0 - 1.0) * velocityRange);
}

ivec2 stateTexel(int page, int localAgentId) {
    int pageColumns = int(MacroField.w + 0.5);
    int pageSize = textureSize(PositionStateA, 0).x / pageColumns;
    int stateSize = int(PageParams[page].y + 0.5);
    ivec2 pageOrigin = ivec2(page % pageColumns, page / pageColumns) * pageSize;
    return pageOrigin + ivec2(localAgentId % stateSize, localAgentId / stateSize);
}

vec4 currentPositionState(int page, ivec2 texel) {
    return PageParams[page].z > 0.5
            ? texelFetch(PositionStateA, texel, 0)
            : texelFetch(PositionStateB, texel, 0);
}

vec4 previousPositionState(int page, ivec2 texel) {
    if (PageParams[page].w < 0.5) {
        return currentPositionState(page, texel);
    }
    return PageParams[page].z > 0.5
            ? texelFetch(PositionStateB, texel, 0)
            : texelFetch(PositionStateA, texel, 0);
}

vec4 currentVelocityState(int page, ivec2 texel) {
    return PageParams[page].z > 0.5
            ? texelFetch(VelocityStateA, texel, 0)
            : texelFetch(VelocityStateB, texel, 0);
}

vec4 previousVelocityState(int page, ivec2 texel) {
    if (PageParams[page].w < 0.5) {
        return currentVelocityState(page, texel);
    }
    return PageParams[page].z > 0.5
            ? texelFetch(VelocityStateB, texel, 0)
            : texelFetch(VelocityStateA, texel, 0);
}

vec4 currentBehaviorState(int page, ivec2 texel) {
    return PageParams[page].z > 0.5
            ? texelFetch(BehaviorStateA, texel, 0)
            : texelFetch(BehaviorStateB, texel, 0);
}

vec4 previousBehaviorState(int page, ivec2 texel) {
    if (PageParams[page].w < 0.5) {
        return currentBehaviorState(page, texel);
    }
    return PageParams[page].z > 0.5
            ? texelFetch(BehaviorStateB, texel, 0)
            : texelFetch(BehaviorStateA, texel, 0);
}

void selectPage(int page) {
    CrowdAnchor = PageAnchors[page];
    DrawParams = vec4(
            PageParams[page].x,
            0.0,
            PageParams[page].y,
            MacroLayout.x);
    DrawLayout = vec4(MacroLayout.y, MacroLayout.z, 0.0, 0.0);
    FieldParams = vec4(MacroField.xyz, 0.0);
    int pageColumns = int(MacroField.w + 0.5);
    int fieldSize = int(MacroField.x + 0.5);
    CurrentFieldOrigin = ivec2(page % pageColumns, page / pageColumns) * fieldSize;
}

ivec2 fieldTexel(vec2 position) {
    vec2 extent = vec2(DrawParams.w, DrawLayout.x);
    vec2 normalized = position / (extent * 2.0) + 0.5;
    int fieldSize = int(FieldParams.x + 0.5);
    return clamp(ivec2(floor(normalized * FieldParams.x)), ivec2(0), ivec2(fieldSize - 1));
}

float heightAt(ivec2 texel) {
    vec4 fieldSample = texelFetch(MacroStaticFields, CurrentFieldOrigin + texel, 0);
    return (decode16(fieldSample.gb) * 2.0 - 1.0) * FieldParams.y;
}

float continuousHeight(vec2 position) {
    int fieldSize = int(FieldParams.x + 0.5);
    ivec2 fieldMaximum = ivec2(fieldSize - 1);
    vec2 extent = vec2(DrawParams.w, DrawLayout.x);
    vec2 gridPosition = position + extent;
    ivec2 center = ivec2(floor(gridPosition));
    vec2 local = fract(gridPosition);
    ivec2 low = center;
    ivec2 high = center;
    vec2 blend = vec2(0.0);
    if (local.x < HEIGHT_TRANSITION_BAND) {
        low.x -= 1;
        blend.x = 0.5 + 0.5 * smoothstep(0.0, HEIGHT_TRANSITION_BAND, local.x);
    } else if (local.x > 1.0 - HEIGHT_TRANSITION_BAND) {
        high.x += 1;
        blend.x = 0.5 * smoothstep(1.0 - HEIGHT_TRANSITION_BAND, 1.0, local.x);
    }
    if (local.y < HEIGHT_TRANSITION_BAND) {
        low.y -= 1;
        blend.y = 0.5 + 0.5 * smoothstep(0.0, HEIGHT_TRANSITION_BAND, local.y);
    } else if (local.y > 1.0 - HEIGHT_TRANSITION_BAND) {
        high.y += 1;
        blend.y = 0.5 * smoothstep(1.0 - HEIGHT_TRANSITION_BAND, 1.0, local.y);
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
        float walkable = texelFetch(
                MacroStaticFields,
                CurrentFieldOrigin + cells[sampleIndex],
                0).r > 0.5 ? 1.0 : 0.0;
        float weight = weights[sampleIndex] * walkable;
        weightedHeight += heightAt(cells[sampleIndex]) * weight;
        totalWeight += weight;
    }
    return totalWeight > 0.0001
            ? weightedHeight / totalWeight
            : heightAt(fieldTexel(position));
}

vec3 decodeAnimationPosition(
        int appearanceId,
        int vertexId,
        int row,
        int lookPose) {
    ivec2 origin = ivec2(AnimationRegions[appearanceId].xy + 0.5);
    int column = origin.x + vertexId * 6 + lookPose * 2;
    int atlasRow = origin.y + row;
    vec4 encodedXY = texelFetch(AnimationFrames, ivec2(column, atlasRow), 0);
    vec4 encodedZ = texelFetch(AnimationFrames, ivec2(column + 1, atlasRow), 0);
    float range = AppearanceMotions[appearanceId].y;
    return vec3(
            (decode16(encodedXY.rg) * 2.0 - 1.0) * range,
            (decode16(encodedXY.ba) * 2.0 - 1.0) * range,
            (decode16(encodedZ.rg) * 2.0 - 1.0) * range);
}

vec3 animationFrame(
        int appearanceId,
        int vertexId,
        int rowOffset,
        int frameCount,
        float phase,
        int lookPose) {
    float frame = fract(phase) * float(frameCount);
    int frame0 = int(floor(frame)) % frameCount;
    int frame1 = (frame0 + 1) % frameCount;
    return mix(
            decodeAnimationPosition(
                    appearanceId, vertexId, rowOffset + frame0, lookPose),
            decodeAnimationPosition(
                    appearanceId, vertexId, rowOffset + frame1, lookPose),
            fract(frame));
}

uvec4 geometryBytes(int appearanceId, int vertexId, int part) {
    ivec2 origin = ivec2(GeometryRegions[appearanceId].xy + 0.5);
    return uvec4(floor(texelFetch(
            StaticGeometry,
            origin + ivec2(vertexId * 6 + part, 0),
            0)
            * 255.0 + 0.5));
}

float geometryFloat(int appearanceId, int vertexId, int part) {
    uvec4 bytes = geometryBytes(appearanceId, vertexId, part);
    uint bits = bytes.r | (bytes.g << 8u) | (bytes.b << 16u) | (bytes.a << 24u);
    return uintBitsToFloat(bits);
}

float signedGeometryByte(uint value) {
    int signedValue = int(value);
    if (signedValue > 127) {
        signedValue -= 256;
    }
    return clamp(float(signedValue) / 127.0, -1.0, 1.0);
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
    float transition = smoothstep(0.80, 1.0, fract(time));
    float target = mix(
            gazeTarget(agentId, segment),
            gazeTarget(agentId, segment + 1.0),
            transition);
    float walking = smoothstep(nominalSpeed * 0.10, nominalSpeed * 0.55, speed);
    return target * MAX_LOOK_YAW * mix(0.70, 0.34, walking);
}

int appearanceForVertex(int expandedVertexId) {
    int low = 0;
    int high = APPEARANCE_COUNT;
    // 对 28 个有序 exclusiveEndVertex 执行固定五轮 lower_bound，避免动态循环展开差异。
    for (int iteration = 0; iteration < 5; iteration++) {
        int middle = (low + high) / 2;
        int exclusiveEnd = int(BatchRanges[middle].y + 0.5);
        if (expandedVertexId < exclusiveEnd) {
            high = middle;
        } else {
            low = middle + 1;
        }
    }
    return min(low, APPEARANCE_COUNT - 1);
}

uvec2 directoryAgent(int directoryIndex) {
    int directoryWidth = int(MacroLayout.w + 0.5);
    ivec2 texel = ivec2(
            directoryIndex % directoryWidth,
            directoryIndex / directoryWidth);
    uvec3 encoded = uvec3(floor(
            texelFetch(RenderAgentDirectory, texel, 0).rgb * 255.0 + 0.5));
    return uvec2(encoded.r, encoded.g * 256u + encoded.b);
}

ExpandedVertex expandVertex(int expandedVertexId, int requiredAttributes) {
    ExpandedVertex result;
    result.position = vec3(0.0);
    result.color = vec4(0.0);
    result.uv = vec2(0.0);
    result.overlay = uvec2(0u, 10u);
    result.light = uvec2(0u);
    result.normal = vec3(0.0);
    result.middleUv = vec2(0.0);
    result.tangent = vec4(0.0);
    if ((requiredAttributes & APPEARANCE_ATTRIBUTES) == 0) {
        return result;
    }

    int appearanceId = appearanceForVertex(expandedVertexId);
    int batchVertexId = expandedVertexId - int(BatchRanges[appearanceId].x + 0.5);
    int modelVertexCount = int(GeometryRegions[appearanceId].z + 0.5) / 6;
    int modelVertexId = batchVertexId % modelVertexCount;
    int instanceId = batchVertexId / modelVertexCount;
    vec4 appearanceLayout = AppearanceLayouts[appearanceId];
    vec4 appearanceMotion = AppearanceMotions[appearanceId];
    int directoryIndex = int(BatchRanges[appearanceId].z + 0.5) + instanceId;
    uvec2 directoryEntry = directoryAgent(directoryIndex);
    int page = int(directoryEntry.x);
    int agentId = int(directoryEntry.y);
    selectPage(page);

    if ((requiredAttributes & ATTRIBUTE_UV) != 0) {
        vec2 localUv = vec2(
                geometryFloat(appearanceId, modelVertexId, 0),
                geometryFloat(appearanceId, modelVertexId, 1));
        result.uv = DiffuseRegions[appearanceId].xy
                + localUv * DiffuseRegions[appearanceId].zw;
    }
    if ((requiredAttributes & ATTRIBUTE_MIDDLE_UV) != 0) {
        vec2 localMiddleUv = vec2(
                geometryFloat(appearanceId, modelVertexId, 3),
                geometryFloat(appearanceId, modelVertexId, 4));
        result.middleUv = DiffuseRegions[appearanceId].xy
                + localMiddleUv * DiffuseRegions[appearanceId].zw;
    }

    bool needsAgentPosition = (requiredAttributes
            & (ATTRIBUTE_POSITION | ATTRIBUTE_COLOR | ATTRIBUTE_LIGHT)) != 0;
    bool needsMotion = (requiredAttributes
            & (ATTRIBUTE_POSITION | ATTRIBUTE_NORMAL | ATTRIBUTE_TANGENT)) != 0;
    if (!needsAgentPosition && !needsMotion) {
        return result;
    }

    ivec2 texel = stateTexel(page, agentId);
    vec4 previousPositionEncoded = previousPositionState(page, texel);
    vec4 currentPositionEncoded = currentPositionState(page, texel);
    bool newlyActivated = all(equal(previousPositionEncoded, vec4(0.0)));
    vec2 previousPosition = vec2(0.0);
    vec2 currentPosition = vec2(0.0);
    vec2 agentPosition = vec2(0.0);
    if (needsAgentPosition || needsMotion) {
        previousPosition = decodePosition(previousPositionEncoded);
        currentPosition = decodePosition(currentPositionEncoded);
        if (newlyActivated) {
            previousPosition = currentPosition;
        }
    }
    if (needsAgentPosition) {
        agentPosition = mix(previousPosition, currentPosition, CrowdAnchor.w);
    }

    vec2 actualVelocity = vec2(0.0);
    float facingAngle = 0.0;
    vec2 forward = vec2(1.0, 0.0);
    vec2 right = vec2(0.0, 1.0);
    float speed = 0.0;
    float nominalSpeed = max(appearanceMotion.x, 0.05);
    float lookYaw = 0.0;
    uvec4 normalAndLook = uvec4(0u);
    bool lookAffected = false;
    if (needsMotion) {
        vec2 currentIntendedVelocity = decodeVelocity(
                currentVelocityState(page, texel));
        vec2 previousIntendedVelocity = newlyActivated
                ? currentIntendedVelocity
                : decodeVelocity(previousVelocityState(page, texel));
        // 头部目标沿用状态插值，避免在 0.1 秒模拟步边界瞬移到新流向。
        vec2 intendedVelocity = mix(
                previousIntendedVelocity,
                currentIntendedVelocity,
                CrowdAnchor.w);
        float previousFacing = decodeHeading(previousBehaviorState(page, texel));
        float currentFacing = decodeHeading(currentBehaviorState(page, texel));
        if (newlyActivated) {
            previousFacing = currentFacing;
        }
        actualVelocity = (currentPosition - previousPosition)
                / SIMULATION_STEP_SECONDS;
        float facingDelta = atan(
                sin(currentFacing - previousFacing),
                cos(currentFacing - previousFacing));
        facingAngle = previousFacing + facingDelta * CrowdAnchor.w;
        speed = length(actualVelocity);
        // 身体读取限速后的持久朝向，位移只决定 walking/idle，不能覆盖朝向。
        forward = vec2(cos(facingAngle), sin(facingAngle));
        right = vec2(-forward.y, forward.x);
        float intendedFacing = length(intendedVelocity) > nominalSpeed * 0.08
                ? atan(intendedVelocity.y, intendedVelocity.x)
                : facingAngle;
        float turnLead = atan(
                sin(intendedFacing - facingAngle),
                cos(intendedFacing - facingAngle));
        float ambient = ambientGaze(agentId, speed, nominalSpeed);
        float turnDominance = smoothstep(0.16, 0.55, abs(turnLead));
        lookYaw = clamp(
                turnLead * 0.88 + ambient * (1.0 - turnDominance),
                -MAX_LOOK_YAW,
                MAX_LOOK_YAW);
        normalAndLook = geometryBytes(appearanceId, modelVertexId, 2);
        lookAffected = normalAndLook.a > 127u;
    }

    bool hidden = false;

    if ((requiredAttributes & ATTRIBUTE_POSITION) != 0) {
        int idleFrames = max(int(appearanceLayout.z + 0.5), 1);
        int walkFrames = max(int(appearanceLayout.w + 0.5), 1);
        float phaseOffset = hash11(float(agentId) + 101.0);
        vec3 idlePosition = animationFrame(
                appearanceId, modelVertexId, 0, idleFrames,
                DrawParams.x / max(appearanceMotion.z, 0.01) + phaseOffset, 0);
        vec3 walkPosition = animationFrame(
                appearanceId, modelVertexId, idleFrames, walkFrames,
                DrawParams.x / max(appearanceMotion.w, 0.01) + phaseOffset, 0);
        float walkingWeight = smoothstep(
                nominalSpeed * 0.12,
                nominalSpeed * 0.48,
                speed);
        vec3 local = mix(idlePosition, walkPosition, walkingWeight);
        if (lookAffected) {
            int lookPose = lookYaw >= 0.0 ? 1 : 2;
            vec3 idleLook = animationFrame(
                    appearanceId, modelVertexId, 0, idleFrames,
                    DrawParams.x / max(appearanceMotion.z, 0.01) + phaseOffset,
                    lookPose);
            vec3 walkLook = animationFrame(
                    appearanceId, modelVertexId, idleFrames, walkFrames,
                    DrawParams.x / max(appearanceMotion.w, 0.01) + phaseOffset,
                    lookPose);
            local = mix(
                    local,
                    mix(idleLook, walkLook, walkingWeight),
                    abs(lookYaw) / MAX_LOOK_YAW);
        }
        vec2 facingHorizontal = right * local.x - forward * local.z;
        vec3 facingLocal = vec3(facingHorizontal.x, local.y, facingHorizontal.y);
        result.position = hidden
                ? vec3(1.0e20)
                : CrowdAnchor.xyz
                + vec3(
                        agentPosition.x,
                        continuousHeight(agentPosition),
                        agentPosition.y)
                + facingLocal;
    }
    if ((requiredAttributes & ATTRIBUTE_COLOR) != 0) {
        float tint = mix(0.88, 1.04, hash11(float(agentId) + 4.0));
        result.color = vec4(vec3(tint), hidden ? 0.0 : 1.0);
    }
    if ((requiredAttributes & ATTRIBUTE_LIGHT) != 0) {
        vec2 encodedLight = texelFetch(
                MacroLightFields,
                CurrentFieldOrigin + fieldTexel(agentPosition),
                0).rg;
        result.light = uvec2(floor(encodedLight * 15.0 + 0.5)) * 16u;
    }
    if ((requiredAttributes & ATTRIBUTE_NORMAL) != 0) {
        vec3 localNormal = normalize(vec3(
                signedGeometryByte(normalAndLook.r),
                signedGeometryByte(normalAndLook.g),
                signedGeometryByte(normalAndLook.b)));
        if (lookAffected) {
            float lookCos = cos(lookYaw);
            float lookSin = sin(lookYaw);
            localNormal.xz = mat2(
                    lookCos,
                    lookSin,
                    -lookSin,
                    lookCos) * localNormal.xz;
        }
        vec2 normalHorizontal = right * localNormal.x - forward * localNormal.z;
        result.normal = normalize(vec3(
                normalHorizontal.x,
                localNormal.y,
                normalHorizontal.y));
    }
    if ((requiredAttributes & ATTRIBUTE_TANGENT) != 0) {
        uvec4 tangentBytes = geometryBytes(appearanceId, modelVertexId, 5);
        vec4 localTangent = vec4(
                signedGeometryByte(tangentBytes.r),
                signedGeometryByte(tangentBytes.g),
                signedGeometryByte(tangentBytes.b),
                signedGeometryByte(tangentBytes.a));
        if (lookAffected) {
            float lookCos = cos(lookYaw);
            float lookSin = sin(lookYaw);
            localTangent.xz = mat2(
                    lookCos,
                    lookSin,
                    -lookSin,
                    lookCos) * localTangent.xz;
        }
        vec2 tangentHorizontal = right * localTangent.x - forward * localTangent.z;
        result.tangent = vec4(
                normalize(vec3(
                        tangentHorizontal.x,
                        localTangent.y,
                        tangentHorizontal.y)),
                localTangent.w);
    }
    return result;
}

int attributeMask(int offset, int layoutKind) {
    if (offset < 12) {
        return ATTRIBUTE_POSITION;
    }
    if (offset < 16) {
        return ATTRIBUTE_COLOR;
    }
    if (offset < 24) {
        return ATTRIBUTE_UV;
    }
    if (offset < 28) {
        return ATTRIBUTE_OVERLAY;
    }
    if (offset < 32) {
        return ATTRIBUTE_LIGHT;
    }
    if (offset < 35) {
        return ATTRIBUTE_NORMAL;
    }
    if (offset == 35 || layoutKind == 0) {
        return 0;
    }
    if (offset < 42) {
        return 0;
    }
    if (offset < 50) {
        return ATTRIBUTE_MIDDLE_UV;
    }
    if (offset < 54) {
        return ATTRIBUTE_TANGENT;
    }
    return 0;
}

int requiredAttributesForPixel(
        int firstByte,
        int vertexId,
        int stride,
        int layoutKind,
        int expandedVertexCount) {
    int requiredAttributes = 0;
    for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
        int absoluteByte = firstByte + byteIndex;
        int addressedVertex = absoluteByte / stride;
        if (addressedVertex == vertexId && addressedVertex < expandedVertexCount) {
            requiredAttributes |= attributeMask(absoluteByte % stride, layoutKind);
        }
    }
    return requiredAttributes;
}

uint floatByte(float value, int byteIndex) {
    return (floatBitsToUint(value) >> uint(byteIndex * 8)) & 255u;
}

uint ushortByte(uint value, int byteIndex) {
    return (value >> uint(byteIndex * 8)) & 255u;
}

uint normalizedByte(float value) {
    return uint(floor(clamp(value, 0.0, 1.0) * 255.0 + 0.5));
}

uint signedNormalizedByte(float value) {
    int encoded = int(round(clamp(value, -1.0, 1.0) * 127.0));
    return uint(encoded) & 255u;
}

uint packedVertexByte(ExpandedVertex vertex, int offset, int layoutKind) {
    if (offset < 12) {
        return floatByte(vertex.position[offset / 4], offset & 3);
    }
    if (offset < 16) {
        return normalizedByte(vertex.color[offset - 12]);
    }
    if (offset < 24) {
        int local = offset - 16;
        return floatByte(vertex.uv[local / 4], local & 3);
    }
    if (offset < 28) {
        int local = offset - 24;
        return ushortByte(vertex.overlay[local / 2], local & 1);
    }
    if (offset < 32) {
        int local = offset - 28;
        return ushortByte(vertex.light[local / 2], local & 1);
    }
    if (offset < 35) {
        return signedNormalizedByte(vertex.normal[offset - 32]);
    }
    if (offset == 35 || layoutKind == 0) {
        return 0u;
    }
    // 无真实 EntityType 时写入 Iris ENTITY 默认三元组 (-1, 0, -1)
    // program 由实体管线选择。
    if (offset < 42) {
        int local = offset - 36;
        return local < 2 || local >= 4 ? 255u : 0u;
    }
    if (offset < 50) {
        int local = offset - 42;
        return floatByte(vertex.middleUv[local / 4], local & 3);
    }
    if (offset < 54) {
        return signedNormalizedByte(vertex.tangent[offset - 50]);
    }
    return 0u;
}

void main() {
    int width = int(BakeTarget.x + 0.5);
    int rowStart = int(BakeTarget.y + 0.5);
    int stride = int(BakeTarget.z + 0.5);
    int layoutKind = int(BakeTarget.w + 0.5);
    int pixelX = int(gl_FragCoord.x);
    int localY = int(gl_FragCoord.y) - rowStart;
    int firstByte = (localY * width + pixelX) * 4;
    int firstVertexId = firstByte / stride;
    int expandedVertexCount = int(BakeCounts.x + 0.5);
    if (firstVertexId >= expandedVertexCount) {
        fragColor = vec4(0.0);
        return;
    }

    int firstRequiredAttributes = requiredAttributesForPixel(
            firstByte,
            firstVertexId,
            stride,
            layoutKind,
            expandedVertexCount);
    ExpandedVertex first = expandVertex(firstVertexId, firstRequiredAttributes);
    uvec4 bytes;
    bytes.r = packedVertexByte(first, firstByte % stride, layoutKind);
    int lastVertexId = (firstByte + 3) / stride;
    if (lastVertexId == firstVertexId) {
        bytes.g = packedVertexByte(first, (firstByte + 1) % stride, layoutKind);
        bytes.b = packedVertexByte(first, (firstByte + 2) % stride, layoutKind);
        bytes.a = packedVertexByte(first, (firstByte + 3) % stride, layoutKind);
    } else {
        int secondRequiredAttributes = requiredAttributesForPixel(
                firstByte,
                lastVertexId,
                stride,
                layoutKind,
                expandedVertexCount);
        ExpandedVertex second = first;
        bool secondValid = lastVertexId < expandedVertexCount;
        if (secondValid) {
            second = expandVertex(lastVertexId, secondRequiredAttributes);
        }
        bytes.g = ((firstByte + 1) / stride == firstVertexId)
                ? packedVertexByte(first, (firstByte + 1) % stride, layoutKind)
                : secondValid
                ? packedVertexByte(second, (firstByte + 1) % stride, layoutKind)
                : 0u;
        bytes.b = ((firstByte + 2) / stride == firstVertexId)
                ? packedVertexByte(first, (firstByte + 2) % stride, layoutKind)
                : secondValid
                ? packedVertexByte(second, (firstByte + 2) % stride, layoutKind)
                : 0u;
        bytes.a = secondValid
                ? packedVertexByte(second, (firstByte + 3) % stride, layoutKind)
                : 0u;
    }
    fragColor = vec4(bytes) / 255.0;
}
