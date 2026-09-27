package tunnel

import (
	"fmt"

	"cfa/blob"

	"github.com/metacubex/mihomo/component/mmdb"
	"github.com/oschwald/maxminddb-golang"
)

func InstallSideloadGeoip(block []byte) error {
	if block != nil {
		if _, err := maxminddb.FromBytes(block); err != nil {
			return fmt.Errorf("load sideload geoip mmdb: %s", err.Error())
		}
	}

	// delegate.Init already consumed the once via LoadFromBytes, so it has to be
	// reset before the override (or the fallback) can be installed.
	mmdb.ReloadIP()

	if block == nil {
		mmdb.LoadFromBytes(blob.GeoipDatabase)
	} else {
		mmdb.LoadFromBytes(block)
	}

	return nil
}
