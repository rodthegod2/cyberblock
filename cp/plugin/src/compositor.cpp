// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
// Cyberpunk 2077 renders with D3D12 only, so everything here goes through reshade::api with the explicit resource
// barriers D3D12 needs: textures live in the shader_resource state and are moved to copy_dest only around uploads.
#include "compositor.h"
#include <windows.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <reshade.hpp>

using namespace reshade::api;

namespace
{
	constexpr const wchar_t *kMappingName = L"Local\\CyberblockFrame";
	constexpr uint32_t kMagic = 0x52464243; // "CBFR"
	constexpr int kHeader = 4096;
	constexpr int kSlotDesc = 256;
	constexpr int kSlotDescBytes = 128;
	constexpr const char *kEffect = "Cyberblock.fx";
	/// A Minecraft frame this old is not shown: Minecraft stalled or quit, and its last picture would hang in the air.
	constexpr float kMaxFrameAgeMs = 250.0f;

	std::atomic<bool> g_registered{false};
	std::atomic<bool> g_active{false};
	std::atomic<float> g_hostNear{0.1f};
	std::atomic<float> g_hostFar{10000.0f};
	std::atomic<uint32_t> g_bbWidth{0}, g_bbHeight{0};
	std::atomic<bool> g_cameraLocked{false};
	std::atomic<float> g_lookLight{-1.0f}, g_lookBias{-1.0f}, g_lookSlope{-1.0f};
	std::atomic<float> g_shakeX{0.0f}, g_shakeY{0.0f}, g_shakeRoll{0.0f}, g_portalWarp{0.0f};
	float g_savedLight = -1.0f, g_savedBias = -1.0f, g_savedSlope = -1.0f;

	struct Pose
	{
		float yaw = 0, pitch = 0, roll = 0, fov = 70;
		double x = 0, y = 0, z = 0;
		bool valid = false;
	};
	std::mutex g_poseLock;
	// Cyberpunk's recent camera poses, newest last. The caller may read the camera Cyberpunk is about to render (the
	// next frame) while the picture being presented was rendered with the one before: re-project to that (g_poseLag back).
	Pose g_hostPoses[4];
	unsigned g_hostPoseCount = 0;
	std::atomic<int> g_poseLag{0};
	Pose g_mcPose;

	HANDLE g_mapping = nullptr;
	const uint8_t *g_view = nullptr;
	int32_t g_slots = 0;
	int64_t g_stride = 0;
	int64_t g_lastPublish = -1;
	DWORD g_nextOpenAttempt = 0;

	struct Layer
	{
		resource tex = {0};
		resource_view srv = {0};
	};
	Layer g_world, g_depth, g_overlay;
	uint32_t g_width = 0, g_height = 0;
	bool g_hasFrame = false;
	std::chrono::steady_clock::time_point g_frameTime; // when the shown frame was uploaded
	float g_mcNear = 0.05f, g_mcFar = 1024.0f;
	int32_t g_mcFlags = 7;

	template <typename T>
	T read(const uint8_t *p)
	{
		T v;
		std::memcpy(&v, p, sizeof(T));
		return v;
	}

	bool open_mapping()
	{
		if (g_view != nullptr)
			return true;
		if (GetTickCount() < g_nextOpenAttempt)
			return false;
		g_nextOpenAttempt = GetTickCount() + 1000;
		g_mapping = OpenFileMappingW(FILE_MAP_READ, FALSE, kMappingName);
		if (g_mapping == nullptr)
			return false;
		const auto *header = static_cast<const uint8_t *>(MapViewOfFile(g_mapping, FILE_MAP_READ, 0, 0, kHeader));
		if (header == nullptr || read<uint32_t>(header) != kMagic)
		{
			if (header != nullptr)
				UnmapViewOfFile(header);
			CloseHandle(g_mapping);
			g_mapping = nullptr;
			return false;
		}
		g_slots = read<int32_t>(header + 12);
		g_stride = read<int64_t>(header + 16);
		UnmapViewOfFile(header);
		// the slot descriptors must fit the header, and the slots must be addressable
		if (g_slots <= 0 || kSlotDesc + kSlotDescBytes * g_slots > kHeader || g_stride <= 0 || g_stride > (int64_t(1) << 40) / g_slots)
		{
			reshade::log::message(reshade::log::level::error, "Cyberblock: frame export header has an invalid slot layout");
			CloseHandle(g_mapping);
			g_mapping = nullptr;
			return false;
		}
		g_view = static_cast<const uint8_t *>(MapViewOfFile(g_mapping, FILE_MAP_READ, 0, 0, static_cast<SIZE_T>(kHeader + g_stride * g_slots)));
		if (g_view == nullptr)
		{
			CloseHandle(g_mapping);
			g_mapping = nullptr;
			return false;
		}
		reshade::log::message(reshade::log::level::info, "Cyberblock: connected to Minecraft's frame export");
		return true;
	}

	/// Points the effect's CB_* textures at our layers; zero handles make ReShade bind its empty texture instead.
	void bind(effect_runtime *runtime)
	{
		runtime->update_texture_bindings("CB_WORLD", g_world.srv, g_world.srv);
		runtime->update_texture_bindings("CB_DEPTH", g_depth.srv, g_depth.srv);
		runtime->update_texture_bindings("CB_OVERLAY", g_overlay.srv, g_overlay.srv);
	}

	void destroy_layers(effect_runtime *runtime)
	{
		const Layer old[3] = {g_world, g_depth, g_overlay};
		g_world = g_depth = g_overlay = Layer();
		g_width = g_height = 0;
		g_hasFrame = false;
		g_lastPublish = -1; // upload the current frame again into the next layers
		// ReShade keeps semantic bindings across effect reloads and runtime resets: drop ours before the views go away
		bind(runtime);
		// D3D12 frees resources at once: run the barriers still recorded on the immediate command list and let the GPU
		// finish every frame that samples or fills the old layers
		command_queue *const queue = runtime->get_command_queue();
		queue->flush_immediate_command_list();
		queue->wait_idle();
		device *const dev = runtime->get_device();
		for (const Layer &layer : old)
		{
			if (layer.srv.handle != 0)
				dev->destroy_resource_view(layer.srv);
			if (layer.tex.handle != 0)
				dev->destroy_resource(layer.tex);
		}
	}

	bool create_layer(device *dev, Layer &layer, uint32_t w, uint32_t h, format fmt)
	{
		if (!dev->create_resource(
				resource_desc(w, h, 1, 1, fmt, 1, memory_heap::default_, resource_usage::shader_resource | resource_usage::copy_dest),
				nullptr, resource_usage::shader_resource, &layer.tex))
			return false;
		return dev->create_resource_view(layer.tex, resource_usage::shader_resource, resource_view_desc(fmt), &layer.srv);
	}

	/// Upload the newest published Minecraft frame, if there is one we haven't shown yet.
	void upload(effect_runtime *runtime)
	{
		const int64_t published = read<int64_t>(g_view + 32);
		if (published == g_lastPublish)
			return;
		const int32_t slot = read<int32_t>(g_view + 40);
		if (slot < 0 || slot >= g_slots)
			return;
		const uint8_t *desc = g_view + kSlotDesc + kSlotDescBytes * slot;
		const int64_t seq = read<int64_t>(desc);
		if (seq & 1)
			return;
		const uint32_t w = read<uint32_t>(desc + 24), h = read<uint32_t>(desc + 28);
		if (w == 0 || h == 0 || uint64_t(w) * h * 4 * 3 > uint64_t(g_stride))
			return; // nothing yet, or the three layers would run past the slot
		command_list *const cmd = runtime->get_command_queue()->get_immediate_command_list();
		if (cmd == nullptr)
			return;
		if (w != g_width || h != g_height)
		{
			if (g_width != 0)
				destroy_layers(runtime);
			device *const dev = runtime->get_device();
			if (!create_layer(dev, g_world, w, h, format::r8g8b8a8_unorm) ||
				!create_layer(dev, g_depth, w, h, format::r32_float) ||
				!create_layer(dev, g_overlay, w, h, format::r8g8b8a8_unorm))
			{
				reshade::log::message(reshade::log::level::error, "Cyberblock: could not create the frame textures");
				destroy_layers(runtime);
				return;
			}
			g_width = w;
			g_height = h;
			bind(runtime);
		}
		const uint8_t *base = g_view + kHeader + g_stride * slot;
		const size_t layer = size_t(w) * h * 4;
		subresource_data data;
		data.row_pitch = w * 4;
		data.slice_pitch = static_cast<uint32_t>(layer);
		// D3D12: the upload goes through the queue's immediate command list (device::update_texture_region uses the one
		// last used on this thread; the command_list overload pins it to this queue, as ReShade's own texture loading
		// does), so the barriers around it go on that same list
		const resource textures[3] = {g_world.tex, g_depth.tex, g_overlay.tex};
		const resource_usage sampled[3] = {resource_usage::shader_resource, resource_usage::shader_resource, resource_usage::shader_resource};
		const resource_usage copied[3] = {resource_usage::copy_dest, resource_usage::copy_dest, resource_usage::copy_dest};
		cmd->barrier(3, textures, sampled, copied);
		for (int i = 0; i < 3; ++i)
		{
			data.data = const_cast<uint8_t *>(base + i * layer);
			cmd->update_texture_region(data, textures[i], 0);
		}
		cmd->barrier(3, textures, copied, sampled);
		if (read<int64_t>(desc) != seq)
			return; // Minecraft rewrote the slot mid-copy: show the next one instead
		g_lastPublish = published;
		g_mcNear = read<float>(desc + 32);
		g_mcFar = read<float>(desc + 36);
		g_mcFlags = read<int32_t>(desc + 44);
		g_mcPose.fov = read<float>(desc + 40);
		g_mcPose.yaw = read<float>(desc + 72);
		g_mcPose.pitch = read<float>(desc + 76);
		g_mcPose.roll = read<float>(desc + 80);
		g_mcPose.x = read<double>(desc + 48);
		g_mcPose.y = read<double>(desc + 56);
		g_mcPose.z = read<double>(desc + 64);
		g_mcPose.valid = true;
		g_hasFrame = true;
		g_frameTime = std::chrono::steady_clock::now();
	}

	/// Reload Cyberblock.fx when the file changes (ReShade doesn't watch it), so tweaks don't need a game restart.
	void watch_effect_file(effect_runtime *runtime)
	{
		static DWORD next = 0;
		static FILETIME last = {};
		static wchar_t path[MAX_PATH] = {};
		if (GetTickCount() < next)
			return;
		next = GetTickCount() + 1000;
		if (path[0] == 0)
		{
			GetModuleFileNameW(nullptr, path, MAX_PATH);
			wchar_t *slash = wcsrchr(path, L'\\');
			if (slash)
				wcscpy_s(slash + 1, MAX_PATH - (slash + 1 - path), L"reshade-shaders\\Shaders\\Cyberblock.fx");
		}
		WIN32_FILE_ATTRIBUTE_DATA info;
		if (!GetFileAttributesExW(path, GetFileExInfoStandard, &info))
			return;
		if (last.dwLowDateTime != 0 && CompareFileTime(&info.ftLastWriteTime, &last) != 0)
			runtime->reload_effect_next_frame(kEffect);
		last = info.ftLastWriteTime;
	}

	void on_present(effect_runtime *runtime)
	{
		watch_effect_file(runtime); // every frame, even when the effect failed to compile
	}

	void set_pose_uniform(effect_runtime *runtime, const char *name, const Pose &p)
	{
		if (const effect_uniform_variable v = runtime->find_uniform_variable(kEffect, name); v.handle != 0)
		{
			const float values[7] = {p.yaw, p.pitch, p.roll, p.fov, float(p.x), float(p.y), float(p.z)};
			runtime->set_uniform_value_float(v, values, 7);
		}
	}

	void on_begin_effects(effect_runtime *runtime, command_list *, resource_view, resource_view)
	{
		uint32_t bw = 0, bh = 0;
		runtime->get_screenshot_width_and_height(&bw, &bh);
		g_bbWidth = bw;
		g_bbHeight = bh;
		auto uniform = [runtime](const char *name) { return runtime->find_uniform_variable(kEffect, name); };
		// the clip planes also drive CyberblockDepthDebug, which works while compositing is off
		if (const effect_uniform_variable v = uniform("cb_host_near"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_hostNear.load());
		if (const effect_uniform_variable v = uniform("cb_host_far"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_hostFar.load());
		// "Show test frames": composite whatever is in the shared memory (replay_frame.py), as rendered, without the link
		bool test = false;
		if (const effect_uniform_variable v = uniform("TestFrames"); v.handle != 0)
			runtime->get_uniform_value_bool(v, &test, 1);

		bool on = (g_active || test) && open_mapping();
		if (on)
			upload(runtime);
		const float age = g_hasFrame ? std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - g_frameTime).count() : 1e9f;
		on = on && g_hasFrame && age <= kMaxFrameAgeMs;
		// The technique stays enabled (preset); cb_active gates it, so Cyberpunk passes through untouched until a
		// Minecraft frame is here. (Toggling techniques from inside this callback crashes ReShade.)
		if (const effect_uniform_variable v = uniform("cb_active"); v.handle != 0)
			runtime->set_uniform_value_bool(v, on);
		if (const effect_uniform_variable v = uniform("cb_age_ms"); v.handle != 0)
			runtime->set_uniform_value_float(v, age);
		if (!on)
			return;
		if (const effect_uniform_variable v = uniform("cb_mc_near"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_mcNear);
		if (const effect_uniform_variable v = uniform("cb_mc_far"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_mcFar);
		if (const effect_uniform_variable v = uniform("cb_mc_flags"); v.handle != 0)
			runtime->set_uniform_value_int(v, g_mcFlags);
		// a scene's look overrides the preset's light matching and depth bias; the preset's values come back after
		auto look = [&](const char *name, float want, float &saved) {
			const effect_uniform_variable v = uniform(name);
			if (v.handle == 0)
				return;
			if (want >= 0.0f)
			{
				if (saved < 0.0f)
					runtime->get_uniform_value_float(v, &saved, 1);
				runtime->set_uniform_value_float(v, want);
			}
			else if (saved >= 0.0f)
			{
				runtime->set_uniform_value_float(v, saved);
				saved = -1.0f;
			}
		};
		look("LightMatch", g_lookLight.load(), g_savedLight);
		look("DepthBias", g_lookBias.load(), g_savedBias);
		look("SlopeBias", g_lookSlope.load(), g_savedSlope);
		if (const effect_uniform_variable v = uniform("cb_shake"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_shakeX.load(), g_shakeY.load(), g_shakeRoll.load());
		if (const effect_uniform_variable v = uniform("cb_portal_warp"); v.handle != 0)
			runtime->set_uniform_value_float(v, g_portalWarp.load());

		// Re-projection (done by the effect) from Minecraft's pose to Cyberpunk's latest, extrapolated by the effect's
		// PosePrediction frames. Without a host pose, a locked camera or test frames, the host pose is Minecraft's own:
		// the frame is composited as rendered.
		Pose host, prev;
		{
			std::lock_guard<std::mutex> lock(g_poseLock);
			const unsigned lag = unsigned(std::clamp(g_poseLag.load(), 0, 2));
			if (g_hostPoseCount > lag)
				host = g_hostPoses[(g_hostPoseCount - 1 - lag) & 3];
			if (g_hostPoseCount > lag + 1)
				prev = g_hostPoses[(g_hostPoseCount - 2 - lag) & 3];
		}
		float predict = 0.0f;
		if (const effect_uniform_variable v = uniform("PosePrediction"); v.handle != 0)
			runtime->get_uniform_value_float(v, &predict, 1);
		const bool warp = host.valid && g_mcPose.valid && !g_cameraLocked && !test;
		if (!warp)
			host = g_mcPose;
		else if (prev.valid && predict != 0.0f)
		{
			auto delta = [](float a, float b) { return std::fmod(a - b + 540.0f, 360.0f) - 180.0f; };
			host.yaw += delta(host.yaw, prev.yaw) * predict;
			host.pitch += (host.pitch - prev.pitch) * predict;
			host.roll += (host.roll - prev.roll) * predict;
			host.x += (host.x - prev.x) * predict;
			host.y += (host.y - prev.y) * predict;
			host.z += (host.z - prev.z) * predict;
		}
		set_pose_uniform(runtime, "cb_host_pose", host);
		set_pose_uniform(runtime, "cb_mc_pose", g_mcPose);
	}

	void on_reloaded_effects(effect_runtime *runtime)
	{
		if (g_width != 0)
			bind(runtime);
	}

	void on_destroy_effect_runtime(effect_runtime *runtime)
	{
		destroy_layers(runtime);
	}
}

namespace compositor
{
	bool try_register(void *module)
	{
		if (g_registered)
			return true;
		if (!reshade::register_addon(module))
			return false;
		reshade::register_event<reshade::addon_event::reshade_begin_effects>(on_begin_effects);
		reshade::register_event<reshade::addon_event::reshade_present>(on_present);
		reshade::register_event<reshade::addon_event::reshade_reloaded_effects>(on_reloaded_effects);
		reshade::register_event<reshade::addon_event::destroy_effect_runtime>(on_destroy_effect_runtime);
		g_registered = true;
		reshade::log::message(reshade::log::level::info, "Cyberblock: registered");
		return true;
	}

	void unregister(void *module)
	{
		if (g_registered.exchange(false))
			reshade::unregister_addon(module);
	}

	void set_active(bool active)
	{
		g_active = active;
	}

	void set_host_planes(float near_clip, float far_clip)
	{
		g_hostNear = near_clip;
		g_hostFar = far_clip;
	}

	void backbuffer_size(int &width, int &height)
	{
		width = int(g_bbWidth.load());
		height = int(g_bbHeight.load());
	}

	void set_camera_locked(bool locked)
	{
		g_cameraLocked = locked;
	}

	void set_screen_fx(float shake_x, float shake_y, float shake_roll, float portal_warp)
	{
		g_shakeX = shake_x;
		g_shakeY = shake_y;
		g_shakeRoll = shake_roll;
		g_portalWarp = portal_warp;
	}

	void set_look(float light_match, float depth_bias, float slope_bias)
	{
		g_lookLight = light_match;
		g_lookBias = depth_bias;
		g_lookSlope = slope_bias;
	}

	void set_host_pose(float yaw, float pitch, float roll, float fov, double x, double y, double z)
	{
		std::lock_guard<std::mutex> lock(g_poseLock);
		g_hostPoses[g_hostPoseCount & 3] = {yaw, pitch, roll, fov, x, y, z, true};
		++g_hostPoseCount;
	}

	void set_pose_lag(int frames)
	{
		g_poseLag = frames;
	}
}
