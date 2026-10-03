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
		/// Set by Cyberblock_SetStance: V is crouched, V is in the air (jump, fall, double jump, air dash, ...), V is held
		/// (in a vehicle, or by a scene, workspot, braindance or moving elevator). Sent as `cam.cr`, `cam.air` and
		/// `cam.held` with every camera frame.
		inline std::atomic<bool> crouched{false};
		inline std::atomic<bool> inAir{false};
		inline std::atomic<bool> held{false};
		/// Set by Cyberblock_SetFlightFollowing: V follows Steve's glide. Sent as `cam.fly`. It stays until set again, and
		/// goes back to false when Cyberblock_SetActive changes the state, when a Minecraft session starts or the link drops,
		/// and when Minecraft reports a landing (`flight` with active false); not when a flight times out (no samples for
		/// 0.5 s, Cyberblock_FlightTimedOut).
		inline std::atomic<bool> flightFollowing{false};
		/// Set by Cyberblock_SetMenu: a Cyberpunk menu is open, so input forwarding pauses. Sent as `cam.menu`.
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
