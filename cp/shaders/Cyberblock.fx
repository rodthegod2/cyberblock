// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
// Composites Minecraft (the Cyberblock mod) into Cyberpunk 2077. Cyberblock.dll (a ReShade add-on) uploads Minecraft's
// latest frame into the CB_WORLD / CB_DEPTH / CB_OVERLAY textures and sets the cb_* uniforms every frame.
//
// world: premultiplied RGBA, drawn where Minecraft is nearer than Night City's depth buffer (both in metres).
// overlay: Minecraft's hand, HUD and screens, always on top.
#include "ReShade.fxh"

// How Night City's raw depth buffer turns into metres (pick it with CyberblockDepthDebug):
//   0  reversed Z, infinite far plane (1 at the near plane, 0 at infinity): metres = near / depth
//   1  reversed Z, finite far plane
//   2  standard Z (0 at the near plane, 1 at the far plane)
#ifndef CB_HOST_DEPTH_MODE
	#define CB_HOST_DEPTH_MODE 0
#endif

texture CbWorldTex : CB_WORLD;
texture CbDepthTex : CB_DEPTH;
texture CbOverlayTex : CB_OVERLAY;
sampler sWorld { Texture = CbWorldTex; AddressU = CLAMP; AddressV = CLAMP; };
sampler sDepth { Texture = CbDepthTex; MinFilter = POINT; MagFilter = POINT; AddressU = CLAMP; AddressV = CLAMP; };
sampler sOverlay { Texture = CbOverlayTex; AddressU = CLAMP; AddressV = CLAMP; };

// Set every frame by the add-on.
// cb_active: true only while a fresh Minecraft frame is uploaded; until then Cyberpunk passes through untouched.
uniform bool cb_active < hidden = true; > = false;
// Night City's camera clip planes (metres).
uniform float cb_host_near < hidden = true; > = 0.1;
uniform float cb_host_far < hidden = true; > = 10000.0;
// Camera poses in Minecraft's convention: yaw, pitch, roll (degrees), vertical fov (degrees), x, y, z. cb_host_pose is
// Cyberpunk's current camera, cb_mc_pose the one Minecraft rendered the uploaded frame with.
uniform float cb_host_pose[7] < hidden = true; > = { 0.0, 0.0, 0.0, 60.0, 0.0, 0.0, 0.0 };
uniform float cb_mc_pose[7] < hidden = true; > = { 0.0, 0.0, 0.0, 60.0, 0.0, 0.0, 0.0 };
// Minecraft's clip planes, and its depth flags: 1 depth in [0, 1], 2 rows bottom-up, 4 reversed Z.
uniform float cb_mc_near < hidden = true; > = 0.05;
uniform float cb_mc_far < hidden = true; > = 1024.0;
uniform int cb_mc_flags < hidden = true; > = 7;
// How long ago the add-on received the uploaded frame (milliseconds); frames older than 250 ms turn cb_active off.
uniform float cb_age_ms < hidden = true; > = 0.0;
// The camera shake, applied to the finished picture so Night City and Minecraft shake together (x, y: fraction of the
// screen height; z: roll, radians), and the nether portal's warp (0..1) while passing through.
uniform float3 cb_shake < hidden = true; > = float3(0.0, 0.0, 0.0);
uniform float cb_portal_warp < hidden = true; > = 0.0;

uniform float DepthBias < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.005; ui_label = "Depth bias (m)";
	ui_tooltip = "How far behind Night City's surface Minecraft may still show (blocks resting on the ground)."; > = 0.04;
uniform float SlopeBias < ui_type = "drag"; ui_min = 0.0; ui_max = 40.0; ui_step = 0.1; ui_label = "Depth bias per grazing slope";
	ui_tooltip = "Extra depth bias where Night City's surface is seen at a grazing angle (its depth changes fast down the screen): Minecraft ground laid into Night City's still shows far away."; > = 0.0;
uniform float MaxBias < ui_type = "drag"; ui_min = 0.1; ui_max = 10.0; ui_step = 0.1; ui_label = "Largest depth bias (m)"; > = 3.0;
uniform float HostDepthScale < ui_type = "drag"; ui_min = 0.5; ui_max = 2.0; ui_step = 0.001; ui_label = "Night City depth scale";
	ui_tooltip = "Calibration: multiplies Night City's linearised depth."; > = 1.0;
uniform float LightMatch < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Match Night City lighting";
	ui_tooltip = "Relight Minecraft by the (blurred) Night City picture around it: darker in shade, tinted by nearby neon."; > = 0.8;
uniform float LightReference < ui_type = "drag"; ui_min = 0.1; ui_max = 1.0; ui_step = 0.01; ui_label = "Neutral brightness";
	ui_tooltip = "Night City brightness at which Minecraft keeps its own colours."; > = 0.42;
uniform float LightTint < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Light colour"; > = 0.55;
uniform float LightBlur < ui_type = "drag"; ui_min = 1.0; ui_max = 6.0; ui_step = 0.1; ui_label = "Light blur (mip)"; > = 3.2;
uniform float GradeMatch < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Match Night City colour grade";
	ui_tooltip = "Give Minecraft the colour of Night City's whole picture (a neon night, a smoggy sunset), as Cyberpunk's own grading does to its world."; > = 0.55;
uniform float HazeStart < ui_type = "drag"; ui_min = 0.0; ui_max = 200.0; ui_step = 1.0; ui_label = "Haze start (m)"; > = 15.0;
uniform float HazeDistance < ui_type = "drag"; ui_min = 10.0; ui_max = 1000.0; ui_step = 1.0; ui_label = "Haze distance (m)";
	ui_tooltip = "Far away, Minecraft fades into the Night City picture around it (Cyberpunk's haze and fog don't reach Minecraft otherwise)."; > = 160.0;
uniform float HazeStrength < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Haze strength"; > = 0.85;
uniform float EdgeSoftness < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Soften Minecraft's edges";
	ui_tooltip = "Anti-alias where Minecraft meets Night City (Cyberpunk's own anti-aliasing ran before Minecraft was added)."; > = 1.0;
uniform float ContactShadow < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Contact shadows";
	ui_tooltip = "Darken Night City's surfaces right next to Minecraft's (under mobs' feet, around blocks)."; > = 0.5;
uniform float ContactRadius < ui_type = "drag"; ui_min = 0.1; ui_max = 3.0; ui_step = 0.05; ui_label = "Contact shadow size (m)"; > = 0.7;
uniform float BloomStrength < ui_type = "drag"; ui_min = 0.0; ui_max = 2.0; ui_step = 0.01; ui_label = "Minecraft glow";
	ui_tooltip = "Bright Minecraft things (lava, fire, the portal) glow over Night City's picture, as its own lights do."; > = 0.55;
uniform float BloomThreshold < ui_type = "drag"; ui_min = 0.0; ui_max = 1.0; ui_step = 0.01; ui_label = "Glow threshold"; > = 0.62;
uniform float Timer < source = "timer"; >;
uniform int DebugView < ui_type = "combo"; ui_items = "Composite\0Night City depth (1 m bands)\0Minecraft depth (1 m bands)\0Depth difference\0"; > = 0;
uniform bool Reproject < ui_label = "Re-project to Cyberpunk's camera"; ui_tooltip = "Move Minecraft's (slightly older) frame onto Cyberpunk's current camera."; > = true;
uniform float PosePrediction < ui_type = "drag"; ui_min = -2.0; ui_max = 3.0; ui_step = 0.05; ui_label = "Pose prediction (frames)";
	ui_tooltip = "Extrapolate Cyberpunk's camera by this many frames before re-projecting."; > = 0.0;
uniform bool TestFrames < ui_label = "Show test frames without the link";
	ui_tooltip = "For cp/plugin/tests/replay_frame.py: composite the shared-memory frame as rendered (no re-projection), even while Cyberblock is off in game."; > = false;

// Night City's picture at quarter size with mips: its blurred levels stand in for the light around each pixel.
texture HostLightTex { Width = BUFFER_WIDTH / 4; Height = BUFFER_HEIGHT / 4; Format = RGBA8; MipLevels = 7; };
sampler sHostLight { Texture = HostLightTex; AddressU = CLAMP; AddressV = CLAMP; };

// The composited world (Night City + Minecraft, no overlay) and, per pixel: Minecraft's coverage, its depth and Night City's.
texture CompositeTex { Width = BUFFER_WIDTH; Height = BUFFER_HEIGHT; Format = RGBA8; };
sampler sComposite { Texture = CompositeTex; AddressU = CLAMP; AddressV = CLAMP; };
texture McInfoTex { Width = BUFFER_WIDTH; Height = BUFFER_HEIGHT; Format = RGBA16F; };
sampler sMcInfo { Texture = McInfoTex; MinFilter = POINT; MagFilter = POINT; AddressU = CLAMP; AddressV = CLAMP; };
// Minecraft's bright parts at quarter size with mips (the glow).
texture McBrightTex { Width = BUFFER_WIDTH / 4; Height = BUFFER_HEIGHT / 4; Format = RGBA16F; MipLevels = 6; };
sampler sMcBright { Texture = McBrightTex; AddressU = CLAMP; AddressV = CLAMP; };

float luma(float3 c)
{
	return dot(c, float3(0.2126, 0.7152, 0.0722));
}

float4 PS_Light(float4 pos : SV_Position, float2 uv : TEXCOORD) : SV_Target
{
	return float4(tex2D(ReShade::BackBuffer, uv).rgb, 1.0);
}

/// Minecraft renders at full daylight, so treat its colour as albedo and light it with Night City's local light; then
/// give it the picture's colour grade and, far away, its haze (fade into the picture around it).
float3 relight(float3 c, float2 uv, float zm)
{
	const float3 L = tex2Dlod(sHostLight, float4(uv, 0, LightBlur)).rgb;
	const float lum = luma(L);
	const float gain = clamp(lum / LightReference, 0.42, 1.3);
	const float3 tint = clamp(lerp(1.0, L / max(lum, 1e-3), LightTint), 0.55, 1.5);
	c = lerp(c, c * gain * tint, LightMatch);
	// the picture's grade, gentled (the game's own grade keeps some colour in everything) and kept off lights (bright
	// things keep their colour: fire, lava, the portal)
	const float3 M = tex2Dlod(sHostLight, float4(0.5, 0.5, 0, 6.0)).rgb;
	const float3 grade = clamp(lerp(1.0, M / max(luma(M), 1e-3), 0.6), 0.6, 1.6);
	c = lerp(c, c * grade, GradeMatch * (1.0 - saturate((max(c.r, max(c.g, c.b)) - 0.45) * 2.5)));
	const float h = saturate((1.0 - exp(-max(zm - HazeStart, 0.0) / HazeDistance)) * HazeStrength);
	const float3 H = tex2Dlod(sHostLight, float4(uv, 0, LightBlur + 1.0)).rgb;
	return lerp(c, H, h);
}

/// Minecraft's raw depth to metres along its view axis (1e9 where it drew nothing), per cb_mc_flags.
float mc_linear(float d)
{
	const float n = cb_mc_near, f = cb_mc_far;
	const bool reversed = (cb_mc_flags & 4) != 0;
	if (reversed ? d <= 0.0 : d >= 1.0)
		return 1e9;
	if (cb_mc_flags & 1)
		return reversed ? n * f / (n + d * (f - n)) : n * f / (f - d * (f - n));
	const float ndc = 2.0 * d - 1.0; // a [-1, 1] depth range, stored as 0.5 * ndc + 0.5
	return 2.0 * n * f / ((f + n) + (reversed ? ndc : -ndc) * (f - n));
}

/// Texture coordinate in Minecraft's frame for a point of its screen (x right, y up, both -1..1).
float2 mc_uv(float2 ndc)
{
	return (cb_mc_flags & 2) ? ndc * 0.5 + 0.5 : float2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}

/// Night City's raw depth to metres along its view axis, per CB_HOST_DEPTH_MODE.
float host_linear(float d)
{
	const float n = cb_host_near, f = cb_host_far;
#if CB_HOST_DEPTH_MODE == 0
	const float z = n / max(d, 1e-7);
#elif CB_HOST_DEPTH_MODE == 1
	const float z = d > 0.0 ? n * f / (n + d * (f - n)) : 1e9;
#else
	const float z = d < 1.0 ? n * f / (f - d * (f - n)) : 1e9;
#endif
	return z * HostDepthScale;
}

float host_depth(float2 uv)
{
	return host_linear(tex2Dlod(ReShade::DepthBuffer, float4(uv, 0, 0)).x);
}

/// Camera-to-world rotation, as Minecraft builds it: rotationYXZ(pi - yaw, -pitch, roll) (camera looks down -z).
float3x3 camera_rotation(float yaw, float pitch, float roll)
{
	float sa, ca, sb, cb, sc, cc;
	sincos(radians(180.0 - yaw), sa, ca);
	sincos(radians(-pitch), sb, cb);
	sincos(radians(roll), sc, cc);
	const float3x3 ry = float3x3(ca, 0.0, sa, 0.0, 1.0, 0.0, -sa, 0.0, ca);
	const float3x3 rx = float3x3(1.0, 0.0, 0.0, 0.0, cb, -sb, 0.0, sb, cb);
	const float3x3 rz = float3x3(cc, -sc, 0.0, sc, cc, 0.0, 0.0, 0.0, 1.0);
	return mul(mul(ry, rx), rz);
}

/// tan(Cyberpunk's vertical fov / 2).
float host_tan()
{
	return tan(radians(cb_host_pose[3]) * 0.5);
}

float3 bands(float z)
{
	const float t = frac(z);
	return lerp(float3(0.1, 0.1, 0.1), float3(1.0, 0.85, 0.3), step(0.5, t)) * saturate(1.5 - z / 100.0);
}

void PS_Composite(float4 pos : SV_Position, float2 uv : TEXCOORD, out float4 outColor : SV_Target0, out float4 outInfo : SV_Target1)
{
	const float3 host = tex2D(ReShade::BackBuffer, uv).rgb;
	outInfo = 0.0;
	outColor = float4(host, 1.0);
	if (!cb_active)
		return;

	// Where this Night City pixel's view ray lands in Minecraft's frame (screen-locked unless re-projected).
	float2 muv = mc_uv(float2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0));
	bool inside = true;
	const float zh = host_depth(uv);
	// how far behind Night City's surface Minecraft may still show: more where that surface is seen at a grazing angle
	const float allow = min(DepthBias + SlopeBias * abs(ddy(zh)), max(MaxBias, DepthBias));
	float zm = 1e9;
	if (Reproject)
	{
		// Rows of R_mc^T R_host turn a ray in Cyberpunk's camera space into Minecraft's camera space; T is Cyberpunk's
		// camera position relative to Minecraft's, in Minecraft's camera space.
		const float3x3 rm = camera_rotation(cb_mc_pose[0], cb_mc_pose[1], cb_mc_pose[2]);
		const float3x3 W = mul(transpose(rm), camera_rotation(cb_host_pose[0], cb_host_pose[1], cb_host_pose[2]));
		const float3 T = mul(float3(cb_host_pose[4] - cb_mc_pose[4], cb_host_pose[5] - cb_mc_pose[5], cb_host_pose[6] - cb_mc_pose[6]), rm);
		const int2 mcSize = tex2Dsize(sWorld);
		const float hostTan = host_tan();
		// March this Night City pixel's ray (in Cyberpunk's current camera) from near to far, moving each point into
		// the camera Minecraft rendered with (rotation + how far the camera moved since). The first point that is
		// behind Minecraft's surface there is where Minecraft is seen: refine it and sample. Thin, close things are
		// found even when the camera has swung sideways.
		const float3 ray = float3((uv.x * 2.0 - 1.0) * hostTan * BUFFER_WIDTH * BUFFER_RCP_HEIGHT, (1.0 - uv.y * 2.0) * hostTan, -1.0);
		const float2 mcScale = float2(tan(radians(cb_mc_pose[3]) * 0.5) * float(mcSize.x) / float(max(mcSize.y, 1)), tan(radians(cb_mc_pose[3]) * 0.5));
		const float zNear = 0.2, zFar = max(min(zh + allow, 400.0), 0.5);
		float2 ndc = 0.0;
		inside = false;
		[loop] for (int i = 0; i < 24; ++i)
		{
			const float z = zNear * pow(zFar / zNear, i / 23.0);
			const float3 pm = mul(W, ray * z) + T;
			if (pm.z > -1e-3)
				continue;
			const float2 n = pm.xy / -pm.z / mcScale;
			if (any(abs(n) > 1.0))
				continue;
			const float zmv = mc_linear(tex2Dlod(sDepth, float4(mc_uv(n), 0, 0)).r);
			if (zmv > -pm.z + 0.03)
				continue; // still in front of whatever Minecraft drew there
			// crossed Minecraft's surface: settle on it (fixed-point on the surface point's Night City depth)
			float zg = z;
			ndc = n;
			[loop] for (int k = 0; k < 3; ++k)
			{
				const float3 mk = mul(W, ray * zg) + T;
				if (mk.z > -1e-3)
					break;
				const float2 nk = mk.xy / -mk.z / mcScale;
				if (any(abs(nk) > 1.0))
					break;
				const float zk = mc_linear(tex2Dlod(sDepth, float4(mc_uv(nk), 0, 0)).r);
				if (zk > 1e8)
					break;
				ndc = nk;
				// the surface point back in Cyberpunk's camera space; its depth there is -z (W is a rotation: W^-1 = W^T)
				zg = -mul(float3(nk * mcScale, -1.0) * zk - T, W).z;
			}
			muv = mc_uv(ndc);
			zm = zg;
			inside = true;
			break;
		}
	}
	const float4 world = inside ? tex2D(sWorld, muv) : 0.0;
	if (!Reproject)
		zm = mc_linear(tex2D(sDepth, muv).r);

	outInfo = float4(0.0, 0.0, zh, 0.0);
	if (DebugView == 1)
	{
		outColor = float4(bands(zh), 1.0);
		return;
	}
	if (DebugView == 2)
	{
		outColor = float4(world.a > 0.0 ? bands(zm) : host * 0.3, 1.0);
		return;
	}
	if (DebugView == 3)
	{
		outColor = float4(world.a > 0.0 ? float3(saturate((zh - zm) * 0.5 + 0.5), saturate(-(zh - zm) * 0.5 + 0.5), 0.0) : host * 0.3, 1.0);
		return;
	}

	const float visible = zm < zh + allow ? 1.0 : 0.0;
	const float cover = world.a * visible;
	outColor = float4(relight(world.rgb, uv, zm) * visible + host * (1.0 - cover), 1.0);
	outInfo = float4(cover, cover > 0.0 ? zm : 0.0, zh, 0.0);
}

/// Minecraft's bright parts (lava, fire, the portal, blazes): what glows.
float4 PS_Bright(float4 pos : SV_Position, float2 uv : TEXCOORD) : SV_Target
{
	const float cover = tex2D(sMcInfo, uv).x;
	const float3 c = tex2D(sComposite, uv).rgb;
	const float l = luma(c);
	return float4(c * (cover * saturate((l - BloomThreshold) / max(1.0 - BloomThreshold, 1e-3)) / max(l, 1e-3)), 1.0);
}

/// The finished picture: the world (shaken as one: Night City and Minecraft together), Minecraft's edges softened,
/// contact shadows on Night City's surfaces next to Minecraft's, Minecraft's glow, the portal's warp, and Minecraft's
/// hand and HUD on top (neither shaken nor warped).
float3 PS_Final(float4 pos : SV_Position, float2 uv : TEXCOORD) : SV_Target
{
	if (!cb_active)
		return tex2D(ReShade::BackBuffer, uv).rgb;
	const float aspect = BUFFER_WIDTH * BUFFER_RCP_HEIGHT;
	const float t = Timer * 0.001;
	// shake: rotate and move the picture a little, zoomed in just enough that no edge shows
	const float amount = length(cb_shake.xy) + abs(cb_shake.z) * 0.5;
	const float zoom = 1.0 + amount * 2.2 + cb_portal_warp * 0.06;
	float2 d = (uv - 0.5) * float2(aspect, 1.0) / zoom;
	// the portal: a swirl and waves, strongest in the middle
	if (cb_portal_warp > 0.0)
	{
		const float r = length(d);
		const float swirl = cb_portal_warp * 1.1 * exp(-r * 2.2) * sin(t * 1.7 + 1.0);
		const float cs = cos(swirl), sn = sin(swirl);
		d = float2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
		d += cb_portal_warp * 0.014 * float2(sin(d.y * 38.0 + t * 7.0), cos(d.x * 31.0 + t * 6.0));
	}
	const float cr = cos(cb_shake.z), sr = sin(cb_shake.z);
	d = float2(d.x * cr - d.y * sr, d.x * sr + d.y * cr) + cb_shake.xy;
	const float2 wuv = d / float2(aspect, 1.0) + 0.5;

	const float2 px = BUFFER_PIXEL_SIZE;
	float3 color = tex2D(sComposite, wuv).rgb;
	const float4 info = tex2D(sMcInfo, wuv);
	// soften where Minecraft meets Night City (Cyberpunk's anti-aliasing never saw Minecraft)
	if (EdgeSoftness > 0.0)
	{
		const float c1 = tex2D(sMcInfo, wuv + float2(px.x, 0)).x, c2 = tex2D(sMcInfo, wuv - float2(px.x, 0)).x;
		const float c3 = tex2D(sMcInfo, wuv + float2(0, px.y)).x, c4 = tex2D(sMcInfo, wuv - float2(0, px.y)).x;
		const float edge = saturate(abs(c1 - info.x) + abs(c2 - info.x) + abs(c3 - info.x) + abs(c4 - info.x));
		if (edge > 0.0)
		{
			const float3 n = tex2D(sComposite, wuv + float2(px.x, 0)).rgb + tex2D(sComposite, wuv - float2(px.x, 0)).rgb
				+ tex2D(sComposite, wuv + float2(0, px.y)).rgb + tex2D(sComposite, wuv - float2(0, px.y)).rgb;
			color = lerp(color, (color * 2.0 + n) / 6.0, edge * EdgeSoftness);
		}
	}
	// contact shadows: Night City surface pixels with Minecraft surfaces close by (in depth), mostly above them
	if (ContactShadow > 0.0 && info.x < 0.5 && info.z < 200.0)
	{
		const float zh = info.z;
		const float r = clamp(ContactRadius / max(zh, 0.5) * BUFFER_HEIGHT / (2.0 * host_tan()), 2.0, 48.0);
		float occ = 0.0, weight = 0.0;
		[unroll] for (int i = 0; i < 10; ++i)
		{
			const float a = i * 2.39996 + 0.6; // golden-angle spiral
			const float rr = r * sqrt((i + 0.5) / 10.0);
			const float2 o = float2(cos(a), sin(a)) * rr;
			const float4 sm = tex2Dlod(sMcInfo, float4(wuv + o * px, 0, 0));
			const float w = o.y < 0.0 ? 1.0 : 0.35; // things stand on the ground: their shadow is below them
			weight += w;
			if (sm.x > 0.5 && abs(sm.y - zh) < ContactRadius * 1.5)
				occ += w * (1.0 - rr / (r + 1.0));
		}
		color *= 1.0 - ContactShadow * saturate(occ / max(weight, 1e-3) * 2.2);
	}
	// Minecraft's glow
	if (BloomStrength > 0.0)
	{
		const float3 b = tex2Dlod(sMcBright, float4(wuv, 0, 1.0)).rgb * 0.5 + tex2Dlod(sMcBright, float4(wuv, 0, 2.5)).rgb * 0.8
			+ tex2Dlod(sMcBright, float4(wuv, 0, 4.0)).rgb;
		color += b * BloomStrength;
	}
	// the portal's purple
	if (cb_portal_warp > 0.0)
	{
		const float r = length((uv - 0.5) * float2(aspect, 1.0));
		const float3 purple = color * float3(0.72, 0.38, 1.25) + float3(0.12, 0.0, 0.22) * (0.6 + 0.4 * sin(t * 3.0 + r * 9.0));
		color = lerp(color, purple, saturate(cb_portal_warp * (0.55 + r * 0.6)));
	}
	// hand and HUD are screen-space: never shaken
	const float4 overlay = tex2D(sOverlay, mc_uv(float2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0)));
	return overlay.rgb + saturate(color) * (1.0 - overlay.a);
}

/// Night City's depth in metres: black at 0 m to white at 50 m, with a red line every 5 m (task F1/F2: the ramp must
/// follow the scene, and CB_HOST_DEPTH_MODE / "Night City depth scale" are right when the lines sit at true distances).
float3 PS_DepthDebug(float4 pos : SV_Position, float2 uv : TEXCOORD) : SV_Target
{
	const float z = host_depth(uv);
	// one pixel wide: fwidth is how much the depth changes to the next pixel (no lines across silhouettes)
	const float w = fwidth(z);
	const bool isoline = abs(z - 5.0 * round(z / 5.0)) <= w && w < 1.0 && z > 2.5;
	return isoline ? float3(1.0, 0.15, 0.1) : saturate(z / 50.0).xxx;
}

technique Cyberblock < ui_tooltip = "Minecraft in Night City: composites Minecraft while Cyberblock is on (F9) and Minecraft's frames arrive."; >
{
	pass Light
	{
		VertexShader = PostProcessVS;
		PixelShader = PS_Light;
		RenderTarget = HostLightTex;
	}
	pass Composite
	{
		VertexShader = PostProcessVS;
		PixelShader = PS_Composite;
		RenderTarget0 = CompositeTex;
		RenderTarget1 = McInfoTex;
	}
	pass Bright
	{
		VertexShader = PostProcessVS;
		PixelShader = PS_Bright;
		RenderTarget = McBrightTex;
	}
	pass Final
	{
		VertexShader = PostProcessVS;
		PixelShader = PS_Final;
	}
}

technique CyberblockDepthDebug < ui_tooltip = "Night City's depth as Cyberblock reads it: black at 0 m, white at 50 m, a red line every 5 m."; >
{
	pass
	{
		VertexShader = PostProcessVS;
		PixelShader = PS_DepthDebug;
	}
}
