// Cyberpunk's time of day and weather for Minecraft (megaplan 2026-10-04 task 3-S1, section 1.8 amendment item 3, 1.9
// `env`). Once a second while a player exists, the link is up and no save is being written, Cyberblock_SetEnv sends the
// game time (hours, minutes) and the raw weather preset name; the DLL maps the name to the `env.weather` enum and drops
// repeats.
//
// Weather (Phase 0 "Extra: time and weather"; the CET line ENV was not run, so this is feature-checked at runtime):
// Codeware's WeatherSystem.GetWeatherState() gives the current preset, whose `name` is sent as is. If it returns null,
// the fallback is vanilla's rain reading: n"rain" while GetRainIntensity() > 0, else n"" (the DLL then sends `other`).
// The path in use is logged once, and again whenever it changes: 'env: weather from preset|rain intensity'.
//
// 2.31 script API (NativeDB, redscript): GameInstance.GetTimeSystem(GameInstance).GetGameTime() -> GameTime,
// GameTime.Hours/Minutes(GameTime) -> Int32 (as Hud.reds), GameInstance.GetWeatherSystem(GameInstance) ->
// ref<WeatherSystem>, WeatherSystem.GetRainIntensity() -> Float (orphans.swift). Codeware 1.20.3:
// WeatherSystem.GetWeatherState() -> ref<worldWeatherState>, worldWeatherState.name: CName.

// Declared here (rule 0.14), implemented by Cyberblock.dll (natives.cpp; package 3D).
public native func Cyberblock_SetEnv(hour: Int32, minute: Int32, weather: CName) -> Void;

public class CyberblockEnv {
  // CyberblockSystem ticks since the last send (10 Hz -> 1 Hz).
  private let ticks: Int32;
  // The weather path last logged: "" none yet, "preset" or "rain intensity".
  private let source: String;

  // Every CyberblockSystem tick with a player. send: linked and not writing a save.
  public func Tick(game: GameInstance, send: Bool) -> Void {
    if !send {
      return;
    }
    this.ticks += 1;
    if this.ticks < 10 {
      return;
    }
    this.ticks = 0;
    let time = GameInstance.GetTimeSystem(game).GetGameTime();
    let weather = n"";
    let source = "rain intensity";
    let system = GameInstance.GetWeatherSystem(game);
    if IsDefined(system) {
      let state = system.GetWeatherState();
      if IsDefined(state) {
        weather = state.name;
        source = "preset";
      } else if system.GetRainIntensity() > 0.0 {
        weather = n"rain";
      }
    }
    if NotEquals(source, this.source) {
      this.source = source;
      Cyberblock_Log(s"env: weather from \(source) (\(NameToString(weather)))");
    }
    Cyberblock_SetEnv(GameTime.Hours(time), GameTime.Minutes(time), weather);
  }
}
