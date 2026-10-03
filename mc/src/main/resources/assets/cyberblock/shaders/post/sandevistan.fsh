#version 330
#extension GL_ARB_separate_shader_objects : require

// Cyberblock (original): the Sandevistan's look in a standalone Minecraft (plan retest3 C7, F10). A radial chromatic split,
// a lime/teal grade, a cyan-lime glow on edges (a Sobel on luminance) and a soft vignette. Hosted, the ReShade effect in
// Cyberblock.fx does this for the whole frame instead, so this post effect is only requested while not hosted.

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform SandevistanConfig {
    float Split;
    vec3 Tint;
    float Glow;
};

layout(location = 0) out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 texel = 1.0 / InSize;
    vec2 fromCentre = texCoord - vec2(0.5);
    float radius = length(fromCentre);
    vec2 offset = fromCentre * Split * (0.5 + radius * 2.0);

    // the split: red pushed out, blue pulled in, green stays
    vec3 colour;
    colour.r = texture(InSampler, texCoord + offset).r;
    colour.g = texture(InSampler, texCoord).g;
    colour.b = texture(InSampler, texCoord - offset).b;

    // edges: Sobel on luminance
    float tl = luma(texture(InSampler, texCoord + texel * vec2(-1.0, -1.0)).rgb);
    float tc = luma(texture(InSampler, texCoord + texel * vec2(0.0, -1.0)).rgb);
    float tr = luma(texture(InSampler, texCoord + texel * vec2(1.0, -1.0)).rgb);
    float ml = luma(texture(InSampler, texCoord + texel * vec2(-1.0, 0.0)).rgb);
    float mr = luma(texture(InSampler, texCoord + texel * vec2(1.0, 0.0)).rgb);
    float bl = luma(texture(InSampler, texCoord + texel * vec2(-1.0, 1.0)).rgb);
    float bc = luma(texture(InSampler, texCoord + texel * vec2(0.0, 1.0)).rgb);
    float br = luma(texture(InSampler, texCoord + texel * vec2(1.0, 1.0)).rgb);
    float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
    float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
    float edge = clamp(sqrt(gx * gx + gy * gy) * 2.0, 0.0, 1.0);

    // grade: towards the tint, keeping the luminance
    vec3 graded = mix(colour, luma(colour) * Tint * 1.2, 0.35);
    vec3 edgeColour = mix(vec3(0.2, 1.0, 0.9), vec3(0.7, 1.0, 0.2), texCoord.y);
    graded += edgeColour * edge * Glow * 0.6;

    // vignette
    float vignette = smoothstep(0.85, 0.35, radius);
    graded *= mix(0.55, 1.0, vignette);

    fragColor = vec4(graded, 1.0);
}
