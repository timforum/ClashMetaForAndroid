// +build !debug

package main

import (
	"net/http"
	_ "net/http/pprof"
)

// The release build carries the same pprof endpoint the debug build has, but
// pinned to the loopback interface: nothing on the network can reach it, only
// the device itself and whoever comes in over adb. A screening round that
// looks wedged can then be read out without guessing, by forwarding the port
// and dumping the goroutines:
//
//	adb forward tcp:8899 tcp:8888
//	curl "http://127.0.0.1:8899/debug/pprof/goroutine?debug=2"
func init() {
	go func() {
		_ = http.ListenAndServe("127.0.0.1:8888", nil)
	}()
}
