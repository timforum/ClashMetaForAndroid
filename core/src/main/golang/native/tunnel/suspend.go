package tunnel

// Suspend is intentionally inert.
//
// The previous implementation called provider.Suspend(bool), which only assigned
// to an unexported `suspended` flag inside an `android && cmfa` patch file. Nothing
// ever read that flag, so the call had no effect.
//
// tunnel.OnSuspend is NOT a drop-in replacement: it stores the tunnel status that
// tunnel.isHandle() consults, and isHandle returns false for anything other than
// Running, so calling it would drop all proxied traffic while the screen is off.
// See SuspendModule.kt, which calls Clash.suspendCore(true) on ACTION_SCREEN_OFF.
func Suspend(_ bool) {
}
