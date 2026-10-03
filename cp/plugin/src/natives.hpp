// The redscript natives (plan section 1.4, declared in cp/scripts/Cyberblock/Natives.reds) and the mashup state they
// set, which the camera publish and the input forwarding read.
#pragma once
#include <atomic>

namespace cb
{
	inline constexpr char kVersion[] = "0.1.0"; // keep in step with Query() and project(VERSION) in CMakeLists.txt

	namespace natives
	{
		/// Registers the natives with RTTI (RED4ext post-register callback).
		void register_types();
		void post_register_types();

		/// Set by Cyberblock_SetActive.
		inline std::atomic<bool> active{false};
		inline std::atomic<bool> inVehicle{false};
		/// Set by Cyberblock_SetStance: V is crouched. Sent as `cam.cr` with every camera frame.
		inline std::atomic<bool> crouched{false};
		/// Set by Cyberblock_SetMenu: a Cyberpunk menu is open, so input forwarding pauses.
		inline std::atomic<bool> menuOpen{false};
		/// Set by Cyberblock_SetChoices: a Cyberpunk dialogue or choice list shows two or more choices, so E, Q, 1-4 and the
		/// wheel stay Cyberpunk's. Back to false whenever Cyberblock_SetActive changes the state.
		inline std::atomic<bool> choicesOpen{false};
		/// Set by Cyberblock_SetLookCapture: raw mouse deltas go to Minecraft as `look` (the camera-only flight fallback).
		inline std::atomic<bool> lookCapture{false};
		/// F9 went down: set by the window hook, read and cleared by Cyberblock_ConsumeToggle.
		inline std::atomic<bool> togglePressed{false};
	}
}
