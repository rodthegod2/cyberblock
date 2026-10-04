// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
// Cyberpunk 2077 renders with D3D12 only, so everything here goes through reshade::api with the explicit resource
// barriers D3D12 needs: textures live in the shader_resource state and are moved to copy_dest only around uploads.
// Frames reach the textures through persistently mapped upload buffers and recorded copies, never through
// device::update_texture_region: on D3D12 that creates a buffer per call and waits for the GPU to finish everything.
#include "compositor.h"
#include <windows.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <reshade.hpp>
#include <d3d12.h>
#include <dxgi1_4.h>
#include "feed_capture.hpp"
#include "link.hpp"
#include "log.hpp"
#include "modes.hpp"
#include "natives.hpp"
#include "probes.hpp"
#include "screenfx.hpp"
#include "world.hpp"

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
	std::atomic<bool> g_menu{false}; // re-test 3: a Cyberpunk menu is passed through (cb_menu)
	std::atomic<bool> g_full{false}; // Phase 7: the Afterlife hub, Minecraft full-screen (cb_full)
	/// In the hub the last Minecraft frame stays up this long when Minecraft stalls (a dimension change, a world load).
	constexpr float kMaxFullFrameAgeMs = 2000.0f;
	// re-test 3: the Sandevistan / Kerenzikov screen effect (cb_slowmo)
	std::mutex g_slowmoLock;
	cb::screenfx::SlowMo g_slowmo;
	std::atomic<bool> g_glitchMissingLogged{false}; // Phase 8: the effect has no cb_glitch (logged once)

	double steady_seconds()
	{
		return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
	}
	float g_savedLight = -1.0f, g_savedBias = -1.0f, g_savedSlope = -1.0f;

	struct Pose
	{
		float yaw = 0, pitch = 0, roll = 0, fov = 70;
		double x = 0, y = 0, z = 0;
		bool valid = false;
		cb::view::Body body; // Steve's body for this camera (host poses), or for the uploaded frame's camera (g_mcPose)
	};
	std::mutex g_poseLock;
	// Cyberpunk's recent camera poses, newest last. The caller may read the camera Cyberpunk is about to render (the
	// next frame) while the picture being presented was rendered with the one before: re-project to that (g_poseLag back).
	Pose g_hostPoses[4];
	unsigned g_hostPoseCount = 0;
	std::atomic<int> g_poseLag{0};
	Pose g_mcPose;
	cb::view::BodyRing g_bodies; // the bodies of the recent cams, by frame number (g_poseLock)
	// V moved further than this since the uploaded frame (a teleport, fast travel): Steve is not moved with it
	constexpr double kMaxBodyMove = 8.0;

	HANDLE g_mapping = nullptr;
	const uint8_t *g_view = nullptr;
	int32_t g_slots = 0;
	int64_t g_stride = 0;
	int64_t g_lastPublish = -1;
	DWORD g_nextOpenAttempt = 0;

	/// Upload buffers per layer. The CPU fills one while the GPU may still be copying from the others, so neither waits
	/// on the other (spec section 3); a buffer is refilled only after g_fence shows its last copy has finished.
	constexpr uint32_t kRing = 3;

	struct Layer
	{
		resource tex = {0};
		resource_view srv = {0};
		resource upload[kRing] = {};
		uint8_t *mapped[kRing] = {};
	};
	Layer g_world, g_depth, g_overlay;
	uint32_t g_width = 0, g_height = 0;
	uint32_t g_pitch = 0; // bytes per row in the upload buffers: D3D12 copies need 256-byte aligned rows
	fence g_fence = {0};
	uint64_t g_fenceValue = 0;      // last value queued on g_fence
	uint64_t g_ringDone[kRing] = {}; // g_fence reaches this once the GPU has finished copying from ring slot i
	uint32_t g_ringNext = 0;
	int g_ringPending = -1; // ring slot whose copies are recorded but not yet followed by a fence signal
	bool g_hasFrame = false;
	std::chrono::steady_clock::time_point g_frameTime; // when the shown frame was uploaded
	/// Megaplan 9-F1: frames staged into the upload buffers and the CPU time it took, since the last memory line
	std::atomic<uint64_t> g_stagedFrames{0}, g_stagedNanos{0};
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
		const fence old_fence = g_fence;
		g_world = g_depth = g_overlay = Layer();
		g_width = g_height = g_pitch = 0;
		g_fence = {0};
		g_fenceValue = 0;
		std::fill_n(g_ringDone, kRing, uint64_t(0));
		g_ringNext = 0;
		g_ringPending = -1;
		g_hasFrame = false;
		g_lastPublish = -1; // upload the current frame again into the next layers
		// ReShade keeps semantic bindings across effect reloads and runtime resets: drop ours before the views go away
		bind(runtime);
		// D3D12 frees resources at once: run the barriers and copies still recorded on the immediate command list and let
		// the GPU finish every frame that samples or fills the old layers
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
			for (uint32_t i = 0; i < kRing; ++i)
			{
				if (layer.upload[i].handle == 0)
					continue;
				if (layer.mapped[i] != nullptr)
					dev->unmap_buffer_region(layer.upload[i]);
				dev->destroy_resource(layer.upload[i]);
			}
		}
		if (old_fence.handle != 0)
			dev->destroy_fence(old_fence);
	}

	bool create_layer(device *dev, Layer &layer, uint32_t w, uint32_t h, format fmt)
	{
		if (!dev->create_resource(
				resource_desc(w, h, 1, 1, fmt, 1, memory_heap::default_, resource_usage::shader_resource | resource_usage::copy_dest),
				nullptr, resource_usage::shader_resource, &layer.tex))
			return false;
		if (!dev->create_resource_view(layer.tex, resource_usage::shader_resource, resource_view_desc(fmt), &layer.srv))
			return false;
		// Upload-heap buffers as ReShade's own uploads make them (they stay in the cpu_access state, which D3D12 copies
		// can read), mapped once for their whole life: D3D12 allows the GPU to read a mapped upload buffer.
		for (uint32_t i = 0; i < kRing; ++i)
		{
			void *mapped = nullptr;
			if (!dev->create_resource(resource_desc(uint64_t(g_pitch) * h, memory_heap::upload, resource_usage::copy_source),
					nullptr, resource_usage::cpu_access, &layer.upload[i]) ||
				!dev->map_buffer_region(layer.upload[i], 0, UINT64_MAX, map_access::write_only, &mapped))
				return false;
			layer.mapped[i] = static_cast<uint8_t *>(mapped);
		}
		return true;
	}

	/// Queues a fence signal behind the copies recorded from ring slot g_ringPending, so that slot is refilled only
	/// after the GPU has read it. Called at present, after all of the frame's commands are recorded.
	void signal_upload(effect_runtime *runtime)
	{
		if (g_ringPending < 0)
			return;
		const auto slot = static_cast<uint32_t>(g_ringPending);
		g_ringPending = -1;
		// the signal must follow the copies, which sit on the immediate command list: submit it first (no waiting)
		command_queue *const queue = runtime->get_command_queue();
		queue->flush_immediate_command_list();
		if (queue->signal(g_fence, g_fenceValue + 1))
		{
			g_ringDone[slot] = ++g_fenceValue;
			return;
		}
		// without the signal there is no telling when the GPU is done with the slot: let it finish (not expected)
		reshade::log::message(reshade::log::level::warning, "Cyberblock: could not signal the upload fence; waiting for the GPU");
		queue->wait_idle();
	}

	/// Copies one layer from the shared memory into a mapped upload buffer, row by row when the pitches differ.
	void stage_layer(uint8_t *dst, const uint8_t *src, uint32_t w, uint32_t h)
	{
		const size_t row = size_t(w) * 4;
		if (g_pitch == row)
		{
			std::memcpy(dst, src, row * h);
			return;
		}
		for (uint32_t y = 0; y < h; ++y)
			std::memcpy(dst + size_t(y) * g_pitch, src + size_t(y) * row, row);
	}

	/// Upload the newest published Minecraft frame, if there is one we haven't shown yet. Never waits for the GPU: when
	/// the next upload buffer is still being read, or Minecraft rewrites the slot while we read it, the frame on screen
	/// stays and the next call tries again.
	void upload(effect_runtime *runtime)
	{
		const int64_t published = read<int64_t>(g_view + 32);
		if (published == g_lastPublish)
			return;
		const int32_t slot = read<int32_t>(g_view + 40);
		if (slot < 0 || slot >= g_slots)
			return;
		// Seqlock read: everything read between the two loads of seq is used only if seq was even and did not change.
		const uint8_t *desc = g_view + kSlotDesc + kSlotDescBytes * slot;
		const int64_t seq = read<int64_t>(desc);
		std::atomic_thread_fence(std::memory_order_acquire);
		if (seq & 1)
			return;
		const uint32_t w = read<uint32_t>(desc + 24), h = read<uint32_t>(desc + 28);
		const float mc_near = read<float>(desc + 32), mc_far = read<float>(desc + 36);
		const int32_t mc_flags = read<int32_t>(desc + 44);
		const int64_t hostFrame = read<int64_t>(desc + 16); // the cam Minecraft rendered this frame for (cam.f)
		Pose pose;
		pose.fov = read<float>(desc + 40);
		pose.yaw = read<float>(desc + 72);
		pose.pitch = read<float>(desc + 76);
		pose.roll = read<float>(desc + 80);
		pose.x = read<double>(desc + 48);
		pose.y = read<double>(desc + 56);
		pose.z = read<double>(desc + 64);
		pose.valid = true;
		std::atomic_thread_fence(std::memory_order_acquire);
		if (read<int64_t>(desc) != seq)
			return; // the descriptor is being rewritten: don't resize to a torn size
		if (w == 0 || h == 0 || uint64_t(w) * h * 4 * 3 > uint64_t(g_stride))
			return; // nothing yet, or the three layers would run past the slot
		command_queue *const queue = runtime->get_command_queue();
		command_list *const cmd = queue->get_immediate_command_list();
		if (cmd == nullptr)
			return;
		device *const dev = runtime->get_device();
		if (w != g_width || h != g_height)
		{
			if (g_width != 0)
				destroy_layers(runtime);
			g_pitch = (w * 4 + 255) & ~255u;
			if (!create_layer(dev, g_world, w, h, format::r8g8b8a8_unorm) ||
				!create_layer(dev, g_depth, w, h, format::r32_float) ||
				!create_layer(dev, g_overlay, w, h, format::r8g8b8a8_unorm) ||
				!dev->create_fence(0, fence_flags::none, &g_fence))
			{
				reshade::log::message(reshade::log::level::error, "Cyberblock: could not create the frame textures");
				destroy_layers(runtime);
				return;
			}
			g_width = w;
			g_height = h;
			bind(runtime);
		}
		// copies recorded since the last present (effects rendered from outside it) get their signal before the check
		signal_upload(runtime);
		const uint32_t ring = g_ringNext;
		if (dev->get_completed_fence_value(g_fence) < g_ringDone[ring])
			return; // the GPU is still copying from this buffer

		Layer *const layers[3] = {&g_world, &g_depth, &g_overlay};
		const uint8_t *base = g_view + kHeader + g_stride * slot;
		const size_t layer_bytes = size_t(w) * h * 4;
		const auto staging = std::chrono::steady_clock::now();
		for (int i = 0; i < 3; ++i)
			stage_layer(layers[i]->mapped[ring], base + i * layer_bytes, w, h);
		g_stagedFrames.fetch_add(1);
		g_stagedNanos.fetch_add(uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - staging).count()));
		std::atomic_thread_fence(std::memory_order_acquire);
		if (read<int64_t>(desc) != seq)
			return; // Minecraft rewrote the slot while we copied it: the textures and pose keep the last good frame

		// The copies go on the queue's immediate command list, which the runtime records the effects on and submits at
		// present, so they land before the effect samples the textures; signal_upload fences them at present.
		const resource textures[3] = {g_world.tex, g_depth.tex, g_overlay.tex};
		const resource_usage sampled[3] = {resource_usage::shader_resource, resource_usage::shader_resource, resource_usage::shader_resource};
		const resource_usage copied[3] = {resource_usage::copy_dest, resource_usage::copy_dest, resource_usage::copy_dest};
		cmd->barrier(3, textures, sampled, copied);
		for (int i = 0; i < 3; ++i)
			cmd->copy_buffer_to_texture(layers[i]->upload[ring], 0, g_pitch / 4, h, textures[i], 0);
		cmd->barrier(3, textures, copied, sampled);
		g_ringPending = static_cast<int>(ring);
		g_ringNext = (ring + 1) % kRing;

		g_lastPublish = published;
		g_mcNear = mc_near;
		g_mcFar = mc_far;
		g_mcFlags = mc_flags;
		{
			std::lock_guard<std::mutex> lock(g_poseLock);
			pose.body = g_bodies.find(hostFrame);
		}
		g_mcPose = pose;
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

	/// Logs the game's dedicated video memory use against its budget every 5 s while the add-on runs, so a leak shows
	/// as a climbing number long before D3D12 runs out (2026-10-02: the game crashed with "D3D12MA: Out of memory").
	void report_vram(effect_runtime *runtime)
	{
		static DWORD next = 0;
		static IDXGIAdapter3 *adapter = nullptr;
		if (GetTickCount() < next)
			return;
		next = GetTickCount() + 5000;
		if (adapter == nullptr)
		{
			auto *const d3d = reinterpret_cast<ID3D12Device *>(runtime->get_device()->get_native());
			IDXGIFactory4 *factory = nullptr;
			if (d3d == nullptr || FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory))))
				return;
			factory->EnumAdapterByLuid(d3d->GetAdapterLuid(), IID_PPV_ARGS(&adapter));
			factory->Release();
			if (adapter == nullptr)
				return;
		}
		// System commit too: the 2026-10-02 crash was Windows running out of commit (error 1455), not VRAM.
		MEMORYSTATUSEX mem = {sizeof(mem)};
		GlobalMemoryStatusEx(&mem);
		const unsigned long long commit_used = (mem.ullTotalPageFile - mem.ullAvailPageFile) >> 20;
		DXGI_QUERY_VIDEO_MEMORY_INFO info = {};
		// Minecraft's events queued for redscript: a climbing "dropped" means the scripts are not draining them
		const cb::world::QueueStats events = cb::world::events().stats();
		// Our messages waiting for Minecraft: a climbing "dropped" means Minecraft stopped reading (cb::OutQueue)
		const cb::Link::SendStats sends = cb::link().send_stats();
		// Minecraft's frames uploaded in these 5 s, at what size, and the CPU time their staging took (megaplan 9-F1)
		const uint64_t staged = g_stagedFrames.exchange(0), stagedNanos = g_stagedNanos.exchange(0);
		if (SUCCEEDED(adapter->QueryVideoMemoryInfo(0, DXGI_MEMORY_SEGMENT_GROUP_LOCAL, &info)))
			cb::log::info("memory: vram %llu of %llu MB budget, system commit %llu of %llu MB (frames %s); mc events: "
				"%zu queued, %llu dropped, %llu rejected; to mc: %zu queued, %llu superseded, %llu dropped; "
				"uploads: %.1f a second at %ux%u, staging %.2f ms each",
				info.CurrentUsage >> 20, info.Budget >> 20, commit_used, mem.ullTotalPageFile >> 20,
				g_hasFrame ? "arriving" : "none", events.queued, static_cast<unsigned long long>(events.dropped),
				static_cast<unsigned long long>(events.rejected), sends.queued,
				static_cast<unsigned long long>(sends.superseded), static_cast<unsigned long long>(sends.dropped),
				staged / 5.0, g_width, g_height, staged == 0 ? 0.0 : stagedNanos / 1e6 / double(staged));
		if (commit_used * 100 > (mem.ullTotalPageFile >> 20) * 92)
			cb::log::warn("memory: system commit above 92%% of the limit; Windows may start killing programs");
	}

	void on_present(effect_runtime *runtime)
	{
		signal_upload(runtime);
		watch_effect_file(runtime); // every frame, even when the effect failed to compile
		report_vram(runtime);
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
		on = on && g_hasFrame && age <= (g_full && !test ? kMaxFullFrameAgeMs : kMaxFrameAgeMs);
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
		if (const effect_uniform_variable v = uniform("cb_menu"); v.handle != 0)
			runtime->set_uniform_value_bool(v, g_menu.load() && !test);
		if (const effect_uniform_variable v = uniform("cb_full"); v.handle != 0)
			runtime->set_uniform_value_bool(v, g_full.load() && !g_menu.load() && !test);
		if (const effect_uniform_variable v = uniform("cb_slowmo"); v.handle != 0)
		{
			std::array<float, 4> slowmo;
			{
				std::lock_guard<std::mutex> lock(g_slowmoLock);
				slowmo = g_slowmo.uniform(steady_seconds(), cb::natives::menuOpen.load());
			}
			runtime->set_uniform_value_float(v, slowmo.data(), 4);
		}
		// Phase 8 (8-D1): the Reboot Optics glitch; an older Cyberblock.fx without it shows none (said once per load)
		const std::array<float, 4> glitch = cb::modes::glitch_uniform(cb::steady_seconds(), cb::natives::menuOpen.load());
		if (const effect_uniform_variable v = uniform("cb_glitch"); v.handle != 0)
			runtime->set_uniform_value_float(v, glitch.data(), 4);
		else if (glitch[0] > 0.0f && !g_glitchMissingLogged.exchange(true))
			cb::log::warn("glitch: Cyberblock.fx has no cb_glitch (an older shader is installed); Reboot Optics shows no "
						  "screen glitch until tools.install copies the new one");

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

		// Steve's body: the capsule he was drawn in for the uploaded frame, and how far V has moved since (to the pose the
		// frame is re-projected to, extrapolated as that pose is)
		const cb::view::Capsule &drawn = g_mcPose.body.capsule;
		std::array<float, 4> bodyA{0.0f, 0.0f, 0.0f, 0.0f}, bodyB{0.0f, 0.0f, 0.0f, 0.0f};
		std::array<float, 3> move{0.0f, 0.0f, 0.0f};
		if (warp && drawn.valid && host.body.capsule.valid)
		{
			cb::Vec3 feet = host.body.feet;
			if (prev.valid && prev.body.capsule.valid && predict != 0.0f)
				feet = {feet.x + (feet.x - prev.body.feet.x) * predict, feet.y + (feet.y - prev.body.feet.y) * predict,
						feet.z + (feet.z - prev.body.feet.z) * predict};
			const cb::Vec3 d{feet.x - g_mcPose.body.feet.x, feet.y - g_mcPose.body.feet.y, feet.z - g_mcPose.body.feet.z};
			if (std::hypot(d.x, d.y, d.z) <= kMaxBodyMove)
			{
				bodyA = {float(drawn.a.x), float(drawn.a.y), float(drawn.a.z), float(drawn.radius)};
				bodyB = {float(drawn.b.x), float(drawn.b.y), float(drawn.b.z), 1.0f};
				move = {float(d.x), float(d.y), float(d.z)};
			}
		}
		if (const effect_uniform_variable v = uniform("cb_body_a"); v.handle != 0)
			runtime->set_uniform_value_float(v, bodyA.data(), 4);
		if (const effect_uniform_variable v = uniform("cb_body_b"); v.handle != 0)
			runtime->set_uniform_value_float(v, bodyB.data(), 4);
		if (const effect_uniform_variable v = uniform("cb_body_move"); v.handle != 0)
			runtime->set_uniform_value_float(v, move.data(), 3);
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
		feed_capture::register_events(); // Phase 6: the photo feed (feed_capture.hpp)
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
		if (!active)
		{
			std::lock_guard<std::mutex> lock(g_slowmoLock);
			g_slowmo.clear();
			cb::modes::glitch_clear(); // Phase 8: the glitch belongs to the mashup too
		}
	}

	void set_slowmo(int kind, float seconds)
	{
		std::lock_guard<std::mutex> lock(g_slowmoLock);
		g_slowmo.start(kind == 1 ? cb::screenfx::Kind::Kerenzikov : cb::screenfx::Kind::Sandevistan, seconds, steady_seconds());
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

	void frame_size(int &width, int &height)
	{
		static int loggedW = -1, loggedH = -1;
		int bw = 0, bh = 0;
		backbuffer_size(bw, bh);
		const double scale = cb::world::view_prefs().scale;
		width = scaled_side(bw, scale);
		height = scaled_side(bh, scale);
		if (width > 0 && (width != loggedW || height != loggedH))
		{
			loggedW = width;
			loggedH = height;
			cb::log::info("compositor: Minecraft renders at %.2f of the screen (%dx%d for %dx%d)%s", scale, width, height, bw, bh,
						  width < bw ? "; the effect upscales it" : "");
		}
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

	void set_menu_mode(bool menu)
	{
		if (g_menu.exchange(menu) != menu)
			cb::log::info("compositor: menu pass-through %s", menu ? "on" : "off");
	}

	void set_full_screen(bool full)
	{
		if (g_full.exchange(full) != full)
			cb::log::info("compositor: full-screen Minecraft (the hub) %s", full ? "on" : "off");
	}

	void set_look(float light_match, float depth_bias, float slope_bias)
	{
		g_lookLight = light_match;
		g_lookBias = depth_bias;
		g_lookSlope = slope_bias;
	}

	void set_host_pose(float yaw, float pitch, float roll, float fov, double x, double y, double z, const cb::view::Body &body)
	{
		std::lock_guard<std::mutex> lock(g_poseLock);
		g_hostPoses[g_hostPoseCount & 3] = {yaw, pitch, roll, fov, x, y, z, true, body};
		++g_hostPoseCount;
		if (body.frame >= 0)
			g_bodies.put(body);
	}

	void set_pose_lag(int frames)
	{
		g_poseLag = frames;
	}
}
