package io.github.zolarly.ukiyo.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope

// AGSL 着色器（Android 13 起），逐像素算出星空、星云、光束和黑洞。更早的系统用 Sky.kt 里的 Canvas 画法。

private const val NOISE = """
float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = float2(1.6 * p.x - 1.2 * p.y, 1.2 * p.x + 1.6 * p.y) + 7.3;
        a *= 0.5;
    }
    return v;
}
"""

private const val SPACE = """
uniform float2 res;
uniform float time;
uniform float dp;
uniform float3 wt;
uniform float page;
uniform float2 focus;
uniform float rs;
uniform float2 disk;
uniform float2 sun;
uniform float4 beam;
uniform float power;
$NOISE
// 一层星：每格 cell dp，chance 的格子里有一颗；越亮的越少，最亮的带十字星芒。
float3 stars(float2 q, float cell, float chance, float gain) {
    float2 g = q / cell;
    float2 id = floor(g);
    float h = hash(id + cell);
    if (h > chance) return float3(0.0);
    float2 off = (float2(hash(id + 3.1), hash(id + 7.7)) - 0.5) * 0.5;
    float2 d = (g - id - 0.5 - off) * cell;
    float b = gain * pow(hash(id + 11.3), 6.0);
    float rad = 0.35 + 0.5 * b;
    float twinkle = 0.85 + 0.15 * sin(time * (1.0 + 3.0 * h) + h * 60.0);
    float k = hash(id + 5.9);
    float3 tint = k < 0.25 ? float3(1.0, 0.74, 0.52) : (k < 0.75 ? float3(1.0, 0.96, 0.92) : float3(0.68, 0.8, 1.0));
    float core = exp(-dot(d, d) / (rad * rad));
    float spikes = b > 0.6 ? (exp(-abs(d.x) * 3.0 - abs(d.y) * 0.3) + exp(-abs(d.y) * 3.0 - abs(d.x) * 0.3)) * 0.18 * b : 0.0;
    return tint * (b * core + spikes) * twinkle;
}

// 吸积盘的温度：内圈白热，外圈橙红
float3 heat(float R) {
    return mix(float3(1.0, 0.42, 0.12), float3(1.0, 0.92, 0.78), 1.0 - smoothstep(1.4, 3.6, R));
}

float ring(float R) {
    return smoothstep(1.2, 1.55, R) * (1.0 - smoothstep(3.0, 4.6, R)) * pow(1.5 / R, 2.0);
}

// 盘面纹理：越靠里转得越快。两层错开半个周期交替淡入，免得条纹被越拧越细。
float swirl(float R, float phi) {
    float om = 0.9 * pow(1.3 / R, 1.5);
    float ph = fract(time / 24.0);
    float a1 = phi - ph * 24.0 * om;
    float a2 = phi - fract(ph + 0.5) * 24.0 * om;
    float v1 = fbm(float2(cos(a1), sin(a1)) * 2.2 + float2(0.0, R * 7.0));
    float v2 = fbm(float2(cos(a2), sin(a2)) * 2.2 + float2(5.0, R * 7.0));
    return mix(v2, v1, 1.0 - abs(2.0 * ph - 1.0));
}

half4 main(float2 fc) {
    float send = wt.x;
    float receive = wt.y;
    float2 p = (fc - focus) / rs;
    float r = length(p);

    // 引力透镜：黑洞附近的背景星光被弯折，看到的是偏折后的位置
    float2 src = fc;
    if (receive > 0.0) {
        src = focus + (fc - focus) * (1.0 - receive * 5.8 / max(r * r, 1.0));
    }
    float2 q = src / dp + float2(page * 6.0, 0.0);
    float2 uv = src / res.y;

    float3 col = mix(float3(0.004, 0.006, 0.018), float3(0.016, 0.02, 0.05), fc.y / res.y);

    // 银河：一条斜穿屏幕的光带，中间暖、两边冷，夹着暗色的尘埃带
    float2 o = uv - float2(0.5 * res.x / res.y - page * 0.04, 0.5);
    float across = dot(o, float2(0.894, -0.447));
    float band = exp(-across * across / 0.025);
    if (band > 0.01) {
        float cloud = fbm(uv * 5.0 + 1.7);
        float lane = smoothstep(0.42, 0.68, fbm(uv * 11.0 + float2(4.0, 1.0))) * exp(-across * across / 0.005);
        float3 tone = mix(float3(0.36, 0.42, 0.7), float3(0.95, 0.8, 0.62), exp(-across * across / 0.006));
        col += tone * band * (0.05 + 0.16 * cloud * cloud) * (1.0 - 0.85 * lane);
    }
    col += stars(q, 3.6, 0.1, 0.45 + 1.4 * band);
    col += stars(q + 100.0, 30.0, 0.3, 2.4);

    if (send > 0.0) {
        float2 rel = fc - sun;
        float dist = length(rel) / res.y;
        float ang = atan(rel.y, rel.x);
        // 星云：扭曲过的噪声，朝光源那一侧的边缘被照亮
        float2 np = uv * 2.4 + float2(time * 0.004, -time * 0.003);
        float warp = fbm(np * 1.3 + time * 0.01);
        float dens = fbm(np + warp);
        float dens2 = fbm(np + warp - normalize(rel) * 0.06);
        float lit = clamp((dens - dens2) * 7.0, 0.0, 1.0);
        float glow = exp(-dist * 1.4);
        float dust = smoothstep(0.42, 0.75, dens);
        float3 neb = mix(float3(0.42, 0.12, 0.3), float3(0.08, 0.3, 0.38), smoothstep(0.3, 0.6, warp));
        float3 add = neb * smoothstep(0.25, 0.6, dens) * (0.25 + 0.6 * glow)
                   + float3(1.0, 0.78, 0.5) * lit * dust * glow * 1.4;
        // 丁达尔光：从光源散开的一簇光柱，加上照向中间星球的主光束
        float streak = noise(float2(ang * 40.0, time * 0.06)) * noise(float2(ang * 13.0 + 3.0, time * 0.04 + 5.0));
        float rays = streak * streak * 1.5 * glow * (1.0 - 0.6 * dust);
        float along = dot(rel, beam.xy);
        float len = length(res) * 1.2;
        float wid = beam.z + (beam.w - beam.z) * along / len;
        float perp = abs(rel.x * beam.y - rel.y * beam.x) / wid;
        float shaft = along > 0.0 ? exp(-2.5 * perp * perp) * (1.0 - along / len) * power : 0.0;
        float light = rays + shaft * 0.55;
        // 光里飘着的微尘，只有被照到才看得见
        float motes = stars(q + float2(time * 1.2, time * 0.7), 5.0, 0.25, 2.0).g;
        add += float3(1.0, 0.82, 0.58) * (light * 0.5 + motes * light * 3.0);
        add += float3(1.0, 0.9, 0.75) * (exp(-dist * 7.0) * 0.8 + exp(-dist * 2.5) * 0.25);
        col = col * (1.0 - dust * 0.75 * send) + add * send;
    }

    if (receive > 0.0) {
        float c = cos(disk.x);
        float s = sin(disk.x);
        float2 d = float2(c * p.x + s * p.y, -s * p.x + c * p.y);
        float2 dk = float2(d.x, d.y / disk.y);
        float R = max(length(dk), 0.001);
        col *= mix(1.0, smoothstep(0.96, 1.04, r), receive);

        float3 emit = float3(0.0);
        // 盘面本身：前半圈挡在黑洞前面，后半圈被黑洞挡住
        float inDisk = ring(R);
        if (inDisk > 0.0 && (d.y > 0.0 || r > 1.0)) {
            float v = smoothstep(0.25, 0.8, swirl(R, atan(dk.y, dk.x)));
            float dop = 1.0 + 0.75 * dk.x / R;
            emit += heat(R) * inDisk * (0.25 + 1.3 * v) * dop * 1.3;
        }
        // 盘的背面被引力弯过来，绕着黑洞上方（粗）和下方（细）各成一道弧
        if (r > 1.0) {
            float rr = max(r - 1.03, 0.0);
            bool top = d.y < 0.0;
            float Ra = 1.3 + rr * (top ? 7.0 : 14.0);
            float a = atan(d.y, d.x) - time * 0.5;
            float n = noise(float2(cos(a), sin(a)) * 3.0 + float2(0.0, Ra * 5.0));
            emit += heat(Ra) * ring(Ra) * (0.4 + 0.8 * n) * (top ? 1.7 : 0.6) * (1.0 + 0.6 * d.x / r);
            float x = (r - 1.045) / 0.022;
            emit += float3(1.0, 0.9, 0.75) * exp(-x * x) * (0.9 + 0.5 * d.x / r);
            emit += float3(1.0, 0.6, 0.3) * 0.06 * exp(-rr * 1.2);
        }
        // 随机的耀斑：盘里不时有一团亮斑亮起，随盘转一段再暗下去
        float flare = 0.0;
        for (int k = 0; k < 3; k++) {
            float kk = float(k);
            float cyc = (time + kk * 4.3) / 12.0;
            float id = floor(cyc);
            float life = sin(fract(cyc) * 3.1416);
            float Rf = 1.5 + 1.5 * hash(float2(id, kk + 1.0));
            float af = hash(float2(kk + 2.0, id)) * 6.2832 + time * 0.9 * pow(1.3 / Rf, 1.5);
            float2 rad = float2(cos(af), sin(af));
            float2 dd = dk - rad * Rf;
            float tg = dot(dd, float2(-rad.y, rad.x));
            float nm = dot(dd, rad);
            float vis = (rad.y > 0.0 || r > 1.0) ? 1.0 : 0.0;
            flare += exp(-nm * nm / 0.006 - tg * tg / 0.15) * vis * life * life;
        }
        emit += float3(1.0, 0.95, 0.85) * flare * 1.6;
        col += emit * receive;
    }

    col = 1.0 - exp(-col * 1.4);
    float2 v = fc / res - 0.5;
    col *= 1.0 - 0.5 * dot(v, v);
    return half4(col, 1.0);
}
"""

private const val PLANET = """
uniform float2 center;
uniform float radius;
uniform float time;
uniform float3 light;
$NOISE
half4 main(float2 fc) {
    float2 p = (fc - center) / radius;
    float d = length(p);
    float3 L = normalize(light);
    float3 sky = float3(0.55, 0.6, 1.0);
    if (d > 1.0) {
        // 大气层的一圈薄光，只在朝光的那一侧
        float rim = exp(-(d - 1.0) * 16.0) * clamp(dot(normalize(float3(p, 0.15)), L) + 0.25, 0.0, 1.0) * 0.8;
        return half4(sky * rim, rim);
    }
    float3 n = float3(p, sqrt(1.0 - d * d));
    float ca = cos(0.4);
    float sa = sin(0.4);
    float2 a = float2(ca * p.x + sa * p.y, -sa * p.x + ca * p.y);
    float lat = a.y;
    float lon = atan(a.x, n.z) + time * 0.04;
    float2 uv = float2(lon * 1.4, lat * 3.0);
    // 气态行星的云带，被湍流扭成漩涡，还有一个大风暴
    float w = fbm(uv * 2.0 + float2(time * 0.02, 0.0));
    float bands = sin(lat * 19.0 + w * 1.8 + 0.8 * fbm(uv * 8.0 - time * 0.03));
    float3 col = mix(float3(0.9, 0.84, 0.78), float3(0.78, 0.62, 0.6), smoothstep(-0.8, 0.8, bands));
    col = mix(col, float3(0.52, 0.46, 0.74), smoothstep(0.5, 0.8, w) * 0.45);
    float2 st = float2((lon - 0.6 - time * 0.01) * 2.2, (lat + 0.32) * 7.0);
    col = mix(col, float3(0.95, 0.66, 0.55), exp(-dot(st, st)) * 0.8);
    float ndl = dot(n, L);
    float day = smoothstep(-0.12, 0.45, ndl);
    float limb = pow(n.z, 0.35);
    float fres = pow(1.0 - n.z, 3.0);
    float3 lit = col * day * limb + sky * fres * clamp(ndl + 0.35, 0.0, 1.0) * 0.9 + col * 0.025;
    float aa = clamp((1.0 - d) * radius / 1.5, 0.0, 1.0);
    return half4(lit * aa, aa);
}
"""

fun spaceShader(): RuntimeShader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShader(SPACE) else null

fun planetShader(): RuntimeShader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShader(PLANET) else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun DrawScope.drawSpace(
    shader: RuntimeShader,
    t: Float,
    page: Float,
    send: Float,
    receive: Float,
    mine: Float,
    focus: Offset,
    horizon: Float,
    tilt: Float,
    squash: Float,
    sun: Offset,
    dir: Offset,
    near: Float,
    far: Float,
    power: Float,
) {
    shader.setFloatUniform("res", size.width, size.height)
    shader.setFloatUniform("time", t)
    shader.setFloatUniform("dp", density)
    shader.setFloatUniform("wt", send, receive, mine)
    shader.setFloatUniform("page", page)
    shader.setFloatUniform("focus", focus.x, focus.y)
    shader.setFloatUniform("rs", horizon)
    shader.setFloatUniform("disk", tilt, squash)
    shader.setFloatUniform("sun", sun.x, sun.y)
    shader.setFloatUniform("beam", dir.x, dir.y, near, far)
    shader.setFloatUniform("power", power)
    drawRect(ShaderBrush(shader))
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun DrawScope.drawPlanet(shader: RuntimeShader, t: Float, radius: Float) {
    shader.setFloatUniform("center", center.x, center.y)
    shader.setFloatUniform("radius", radius)
    shader.setFloatUniform("time", t)
    // 和背景里光源的方向一致：左上方，稍微偏向观察者
    shader.setFloatUniform("light", -0.34f, -0.94f, 0.25f)
    drawRect(ShaderBrush(shader))
}
