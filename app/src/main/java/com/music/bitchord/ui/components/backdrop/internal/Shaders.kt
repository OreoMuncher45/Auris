/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
/*
   Copyright 2025 Kyant

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.music.bitchord.ui.components.backdrop.internal

import org.intellij.lang.annotations.Language

@Language("AGSL")
private const val RoundedRectSDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

@Language("AGSL")
internal const val RoundedRectRefractionShaderString = """
uniform shader content;

// These values are computed once on the CPU instead of for every pixel.
uniform float2 halfSize;
uniform float minHalf;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform int circleMapMode;

$RoundedRectSDF

float circleMap(float x) {
    if (circleMapMode == 1) {
        return x * x;
    }
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, minHalf);
    float2 gradBase = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 grad;
    if (depthEffect > 0.5) {
        grad = normalize(gradBase + normalize(centeredCoord));
    } else {
        grad = gradBase;
    }
    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}"""

@Language("AGSL")
internal val RoundedRectRefractionWithDispersionShaderString = """
uniform shader content;

uniform float2 halfSize;
uniform float minHalf;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
uniform int circleMapMode;

$RoundedRectSDF

float circleMap(float x) {
    if (circleMapMode == 1) {
        return x * x;
    }
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, minHalf);
    float2 gradBase = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 grad;
    if (depthEffect > 0.5) {
        grad = normalize(gradBase + normalize(centeredCoord));
    } else {
        grad = gradBase;
    }
    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;
    
    half4 refr = content.eval(refractedCoord);
    half4 refrRed = content.eval(refractedCoord + dispersedCoord);
    half4 refrBlue = content.eval(refractedCoord - dispersedCoord);
    return half4(refrRed.r, refr.g, refrBlue.b, refr.a);
}"""

@Language("AGSL")
internal const val DefaultHighlightShaderString = """
// Compute these shared values once on the CPU instead of for every pixel.
uniform float2 halfSize;
uniform float minHalf;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float2 lightDir;
uniform float falloff;

$RoundedRectSDF

half4 main(float2 coord) {
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float gradRadius = min(radius * 1.5, minHalf);
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float d = dot(grad, lightDir);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}"""

@Language("AGSL")
internal const val AmbientHighlightShaderString = """
// Compute these shared values once on the CPU instead of for every pixel.
uniform float2 halfSize;
uniform float minHalf;
uniform float4 cornerRadii;
uniform float2 lightDir;
uniform float falloff;

$RoundedRectSDF

half4 main(float2 coord) {
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float gradRadius = min(radius * 1.5, minHalf);
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float d = dot(grad, lightDir);
    float intensity = pow(abs(d), falloff);
    float t = step(0.0, d);
    return half4(t, t, t, 1.0) * intensity;
}"""
