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
		/// Written by the per-frame drive (drive.cpp, plan 2026-10-03-retest2 section 1.4), the only writer besides the
		/// resets below: true from the first frame the drive moves V until the flight ends (landing, ground, stop). Sent
		/// as `cam.fly`. It also goes back to false when Cyberblock_SetActive changes the state, when a Minecraft session
		/// starts or the link drops, and when Minecraft reports a landing (`flight` with active false).
		inline std::atomic<bool> flightFollowing{false};
		/// Set by Cyberblock_SetFlightAllowed (10 Hz from CyberblockFlight.Tick): V may be moved (live, not mounted, not
		/// locked). The drive never moves V while it is false. Cleared by Cyberblock_SetActive(false).
		inline std::atomic<bool> flightAllowed{false};
		/// Set by Cyberblock_SetMenu: a Cyberpunk menu is open, so input forwarding pauses. Sent as `cam.menu`.
		inline std::atomic<bool> menuOpen{false};
		/// Set by Cyberblock_SetChoices: a Cyberpunk dialogue or choice list shows two or more choices, so E, Q, 1-4 and the
		/// wheel stay Cyberpunk's. Back to false whenever Cyberblock_SetActive changes the state.
		inline std::atomic<bool> choicesOpen{false};
		/// Set by Cyberblock_SetLookCapture: raw mouse deltas go to Minecraft as `look` (the camera-only flight fallback).
		inline std::atomic<bool> lookCapture{false};
		/// F9 went down: set by the window hook, read and cleared by Cyberblock_ConsumeToggle.
		inline std::atomic<bool> togglePressed{false};

		/// Re-test 3 (plan section 1.2, task D4): set by Cyberblock_SetSeat (10 Hz and on a camera switch while V sits in a
		/// vehicle): V is seated, the vehicle camera is third person, and V's hips point in V's local axes (Y forward, Z up,
		/// metres). The camera sends `cam.seat`, `cam.fp = !seatTpp` and `pl` = feet + the rotated hips point. Cleared by
		/// Cyberblock_SetActive(false) and a link drop.
		inline std::atomic<bool> seated{false};
		inline std::atomic<bool> seatTpp{false};
		inline std::atomic<float> hipsX{0.0f}, hipsY{0.0f}, hipsZ{0.0f};

		/// Re-test 3 (plan section 1.2, task D6): set by Cyberblock_SetPhonePrompt: a call rings or an actionable
		/// notification shows, so T is Cyberpunk's (keys::Context::phone) while no Minecraft screen is open. Cleared by
		/// Cyberblock_SetActive(false) and a link drop.
		inline std::atomic<bool> phonePrompt{false};

		/// The link to Minecraft dropped (main.cpp's drop handler, link thread): forgets the seat and the phone prompt.
		void on_link_dropped();
	}
}
