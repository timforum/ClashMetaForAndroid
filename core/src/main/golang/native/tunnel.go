package main

//#include "bridge.h"
import "C"

import (
	"unsafe"

	"cfa/native/app"
	"cfa/native/tunnel"

	"github.com/metacubex/mihomo/log"
)

// emptyJsonArray is the documented "nothing to report" answer for every list
// query, and doubles as the fallback when one of them panics. It is built once
// because jni_new_string() calls strlen() on whatever it is handed.
var emptyJsonArray = marshalJson([]string{})

//export queryTunnelState
func queryTunnelState() *C.char {
	mode := tunnel.QueryMode()

	response := &struct {
		Mode string `json:"mode"`
	}{mode}

	return marshalJson(response)
}

//export queryNow
func queryNow(upload, download *C.uint64_t) {
	up, down := tunnel.Now()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

//export queryTotal
func queryTotal(upload, download *C.uint64_t) {
	up, down := tunnel.Total()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

// recoverGuard runs fn and swallows any panic, returning fallback instead.
//
// These exports are reached from the UI over binder, so an unrecovered panic
// does not just fail the call: cgo turns it into a runtime abort that takes the
// whole service process down, the VPN goes with it, and the activity that asked
// is finished by the reconnect handler. A read-only query must never be able to
// do that, so every one of them is wrapped.
//
// A nil safe* means the value cannot be produced; the Kotlin side already has a
// defined answer for each of those cases.
func recoverGuard(what string, safe *C.char, fn func() *C.char) (out *C.char) {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("native.%s panicked: %v", what, r)

			out = safe
		}
	}()

	return fn()
}

// recoverVoid is recoverGuard for the exports that return nothing. The caller
// gets no signal either way, but the process survives.
func recoverVoid(what string, fn func()) {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("native.%s panicked: %v", what, r)
		}
	}()

	fn()
}

//export queryGroupNames
func queryGroupNames(excludeNotSelectable C.int) *C.char {
	// An empty JSON array, which is what the Kotlin side decodes into an empty
	// strategy list.
	return recoverGuard("queryGroupNames", emptyJsonArray, func() *C.char {
		return marshalJson(tunnel.QueryProxyGroupNames(excludeNotSelectable != 0))
	})
}

//export queryGroup
func queryGroup(name C.c_string, sortMode C.c_string) *C.char {
	return recoverGuard("queryGroup", nil, func() *C.char {
		n := C.GoString(name)
		s := C.GoString(sortMode)

		mode := tunnel.Default

		switch s {
		case "Title":
			mode = tunnel.Title
		case "Delay":
			mode = tunnel.Delay
		}

		response := tunnel.QueryProxyGroup(n, mode, app.SubtitlePattern())

		if response == nil {
			return nil
		}

		return marshalJson(response)
	})
}

//export patchSelector
func patchSelector(selector, name C.c_string) C.int {
	result := C.int(0)

	recoverGuard("patchSelector", nil, func() *C.char {
		if tunnel.PatchSelector(C.GoString(selector), C.GoString(name)) {
			result = 1
		}

		return nil
	})

	return result
}

//export healthCheck
func healthCheck(completable unsafe.Pointer, name C.c_string) {
	go func(name string) {
		tunnel.HealthCheck(name)

		C.complete(completable, nil)
	}(C.GoString(name))
}

//export healthCheckAll
func healthCheckAll() {
	recoverVoid("healthCheckAll", func() {
		tunnel.HealthCheckAll()
	})
}

//export queryProviders
func queryProviders() *C.char {
	return recoverGuard("queryProviders", emptyJsonArray, func() *C.char {
		return marshalJson(tunnel.QueryProviders())
	})
}

//export updateProvider
func updateProvider(completable unsafe.Pointer, pType C.c_string, name C.c_string) {
	go func(pType, name string) {
		C.complete(completable, marshalString(tunnel.UpdateProvider(pType, name)))

		C.release_object(completable)
	}(C.GoString(pType), C.GoString(name))
}

//export suspend
func suspend(suspended C.int) {
	tunnel.Suspend(suspended != 0)
}

//export installSideloadGeoip
func installSideloadGeoip(block unsafe.Pointer, blockSize C.int) *C.char {
	if block == nil {
		_ = tunnel.InstallSideloadGeoip(nil)

		return nil
	}

	bytes := C.GoBytes(block, blockSize)

	return marshalString(tunnel.InstallSideloadGeoip(bytes))
}
