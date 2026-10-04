// ReShade half of the photo feed (feed_capture.hpp). Everything here runs on the thread that presents (ReShade's
// callbacks), so its state needs no lock; feed::gate() is the only state shared with the link thread.
#include "feed_capture.hpp"
#include <reshade.hpp>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <optional>
#include <span>
#include <utility>
#include "feed.hpp"
#include "link.hpp"
#include "log.hpp"

using namespace reshade::api;

namespace
{
	constexpr const char *kEffect = "Cyberblock.fx";
	constexpr const char *kTechnique = "Cyberblock";
	constexpr const char *kUniform = "cb_feed";
	constexpr const char *kTexture = "CbFeedTex";

	/// Where the capture in flight is: cb_feed set for this frame (Requested), the copy recorded on the immediate command
	/// list (Recorded), a fence signal queued behind it (Fenced). One capture at a time.
	enum class Stage
	{
		Idle,
		Requested,
		Recorded,
		Fenced,
	};
	Stage g_stage = Stage::Idle;
	resource g_readback = {0}; // CbFeedTex's copy in CPU-readable memory
	fence g_fence = {0};
	uint64_t g_fenceValue = 0;
	bool g_bgra = false;  // the read-back's channel order
	double g_costMs = 0.0; // the render thread's time spent on the capture in flight
	cb::feed::CostMonitor g_cost;
	uint64_t g_sent = 0;
	bool g_techniqueOffLogged = false;
	bool g_flagOn = false; // cb_feed was set true for the last frame

	using Clock = std::chrono::steady_clock;

	double now_seconds()
	{
		return std::chrono::duration<double>(Clock::now().time_since_epoch()).count();
	}

	double ms_since(Clock::time_point start)
	{
		return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
	}

	/// The back-buffer feed cannot run, or costs too much: off until Minecraft's next session, logged once.
	void give_up(const char *why)
	{
		g_stage = Stage::Idle;
		if (cb::feed::gate().fall_back())
			cb::log::warn("feed: %s; the back-buffer feed is off, Minecraft's top-down replica render is the fallback", why);
	}

	void release(device *dev)
	{
		if (g_readback.handle != 0)
			dev->destroy_resource(g_readback);
		if (g_fence.handle != 0)
			dev->destroy_fence(g_fence);
		g_readback = {0};
		g_fence = {0};
		g_fenceValue = 0;
		g_stage = Stage::Idle;
	}

	void on_begin_effects(effect_runtime *runtime, command_list *, resource_view, resource_view)
	{
		// a new Minecraft session (hello) reset the gate: the cost check starts over with it
		if (g_cost.tripped() && !cb::feed::gate().fallen_back())
			g_cost.reset();
		if (g_stage == Stage::Requested)
			g_stage = Stage::Idle; // the last frame's effects ended without finish_effects: that capture never happened
		const bool due = g_stage == Stage::Idle && cb::feed::gate().due(now_seconds());
		if (!due && !g_flagOn)
			return; // no feed this frame and cb_feed is already false: nothing to do (the usual case)
		const effect_uniform_variable flag = runtime->find_uniform_variable(kEffect, kUniform);
		bool capture = false;
		if (due)
		{
			const effect_technique technique = runtime->find_technique(kEffect, kTechnique);
			if (flag.handle == 0 || runtime->find_texture_variable(kEffect, kTexture).handle == 0)
				give_up("Cyberblock.fx has no Feed pass (cb_feed, CbFeedTex: an older effect file)");
			else if (!runtime->get_device()->check_capability(device_caps::copy_buffer_to_texture))
				give_up("this graphics device cannot copy a texture back to the CPU");
			else if (technique.handle == 0 || !runtime->get_technique_state(technique))
			{
				if (!std::exchange(g_techniqueOffLogged, true))
					cb::log::warn("feed: the Cyberblock technique is off in ReShade; no feed until it is on (logged once)");
			}
			else
			{
				capture = true;
				g_stage = Stage::Requested;
			}
		}
		if (flag.handle != 0)
			runtime->set_uniform_value_bool(flag, capture);
		g_flagOn = capture;
	}

	void on_finish_effects(effect_runtime *runtime, command_list *cmd, resource_view, resource_view)
	{
		if (g_stage != Stage::Requested)
			return;
		g_stage = Stage::Idle;
		device *const dev = runtime->get_device();
		const effect_texture_variable variable = runtime->find_texture_variable(kEffect, kTexture);
		resource_view srv = {0};
		if (variable.handle != 0)
			runtime->get_texture_binding(variable, &srv, nullptr);
		const resource texture = srv.handle != 0 ? dev->get_resource_from_view(srv) : resource{0};
		if (texture.handle == 0)
			return give_up("CbFeedTex has no texture (the effect did not compile)");
		const resource_desc desc = dev->get_resource_desc(texture);
		const format typed = format_to_default_typed(desc.texture.format, 0);
		if (desc.texture.width != uint32_t(cb::feed::kSize) || desc.texture.height != uint32_t(cb::feed::kSize) ||
			(typed != format::r8g8b8a8_unorm && typed != format::b8g8r8a8_unorm))
			return give_up("CbFeedTex is not 128 x 128 RGBA8");
		if (g_readback.handle == 0)
		{
			// as ReShade's own screenshots do: a read-back "texture" (a buffer with the texture's footprint on D3D12)
			if (!dev->create_resource(resource_desc(desc.texture.width, desc.texture.height, 1, 1, typed, 1, memory_heap::readback,
													resource_usage::copy_dest),
									  nullptr, resource_usage::copy_dest, &g_readback) ||
				!dev->create_fence(0, fence_flags::none, &g_fence))
			{
				release(dev);
				return give_up("could not create the read-back texture or its fence");
			}
			g_bgra = typed == format::b8g8r8a8_unorm;
		}
		const Clock::time_point start = Clock::now(); // creating the read-back once is not the per-frame cost
		cmd->barrier(texture, resource_usage::shader_resource, resource_usage::copy_source);
		cmd->copy_texture_region(texture, 0, nullptr, g_readback, 0, nullptr);
		cmd->barrier(texture, resource_usage::copy_source, resource_usage::shader_resource);
		g_stage = Stage::Recorded;
		g_costMs = ms_since(start);
	}

	void on_present(effect_runtime *runtime)
	{
		if (g_stage == Stage::Recorded)
		{
			// the signal must follow the copy, which sits on the immediate command list: submit it first (no waiting)
			const Clock::time_point start = Clock::now();
			command_queue *const queue = runtime->get_command_queue();
			queue->flush_immediate_command_list();
			if (!queue->signal(g_fence, g_fenceValue + 1))
			{
				g_costMs += ms_since(start);
				return give_up("could not signal the read-back fence");
			}
			++g_fenceValue;
			g_stage = Stage::Fenced;
			g_costMs += ms_since(start);
			return;
		}
		device *const dev = runtime->get_device();
		if (g_stage != Stage::Fenced || dev->get_completed_fence_value(g_fence) < g_fenceValue)
			return; // nothing in flight, or the GPU has not copied it yet: never wait
		g_stage = Stage::Idle;
		const Clock::time_point start = Clock::now();
		subresource_data data = {};
		if (!dev->map_texture_region(g_readback, 0, nullptr, map_access::read_only, &data) || data.data == nullptr)
			return give_up("could not map the read-back texture");
		const size_t bytes = size_t(data.row_pitch) * (cb::feed::kSize - 1) + size_t(cb::feed::kSize) * 4;
		const auto colours = cb::feed::to_map_colours(std::span<const uint8_t>(static_cast<const uint8_t *>(data.data), bytes),
													  data.row_pitch, g_bgra, cb::feed::palette());
		dev->unmap_texture_region(g_readback, 0);
		const auto message = colours ? cb::feed::make_feed(*colours) : std::nullopt;
		if (!message)
			return give_up("the read-back has an unexpected row pitch");
		if (cb::feed::gate().requested()) // Minecraft may have stopped asking while the copy was in flight
			cb::link().send(*message);
		g_costMs += ms_since(start);
		if (g_sent++ == 0 || g_sent % 300 == 0)
			cb::log::info("feed: frame %llu sent (render-thread cost %.3f ms; the limit is %.1f ms)",
						  static_cast<unsigned long long>(g_sent), g_costMs, cb::feed::kMaxCostMs);
		if (g_cost.note(g_costMs))
		{
			char why[128];
			std::snprintf(why, sizeof(why), "the read-back costs %.2f ms of frame time (over %.1f ms %d times in a row)",
						  g_costMs, cb::feed::kMaxCostMs, cb::feed::kSlowInARow);
			give_up(why);
		}
	}

	void on_destroy_effect_runtime(effect_runtime *runtime)
	{
		if (g_readback.handle == 0 && g_fence.handle == 0)
			return;
		// D3D12 frees at once: let the GPU finish a copy still in flight
		if (g_stage == Stage::Recorded || g_stage == Stage::Fenced)
		{
			runtime->get_command_queue()->flush_immediate_command_list();
			runtime->get_command_queue()->wait_idle();
		}
		release(runtime->get_device());
	}
}

namespace feed_capture
{
	void register_events()
	{
		reshade::register_event<reshade::addon_event::reshade_begin_effects>(on_begin_effects);
		reshade::register_event<reshade::addon_event::reshade_finish_effects>(on_finish_effects);
		reshade::register_event<reshade::addon_event::reshade_present>(on_present);
		reshade::register_event<reshade::addon_event::destroy_effect_runtime>(on_destroy_effect_runtime);
	}
}
