package config

import (
	"os"
	P "path"
	"runtime"
	"strings"
	"sync/atomic"

	"cfa/native/app"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/log"
)

// loaded is true while a real profile, not the empty default, is in the core.
// The subscription fetch reads it to decide whether the tunnel has a proxy to
// lend: fetching through the core is the only way the app can reach a
// subscription URL that is blocked on the raw network.
var loaded atomic.Bool

// Loaded reports whether a profile is loaded in this process.
func Loaded() bool {
	return loaded.Load()
}

func logDns(cfg *config.RawConfig) {
	bytes, err := yaml.Marshal(&cfg.DNS)
	if err != nil {
		log.Warnln("Marshal dns: %s", err.Error())

		return
	}

	log.Infoln("dns:")

	for _, line := range strings.Split(string(bytes), "\n") {
		log.Infoln("  %s", line)
	}
}

func UnmarshalAndPatch(profilePath string) (*config.RawConfig, error) {
	configPath := P.Join(profilePath, "config.yaml")

	configData, err := os.ReadFile(configPath)
	if err != nil {
		return nil, err
	}

	rawConfig, err := config.UnmarshalRawConfig(configData)
	if err != nil {
		return nil, err
	}

	if err := process(rawConfig, profilePath); err != nil {
		return nil, err
	}

	return rawConfig, nil
}

func Parse(rawConfig *config.RawConfig) (*config.Config, error) {
	cfg, err := config.ParseRawConfig(rawConfig)
	if err != nil {
		return nil, err
	}

	return cfg, nil
}

func Load(path string) error {
	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	logDns(rawCfg)

	cfg, err := Parse(rawCfg)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	// like hub.Parse()
	hub.ApplyConfig(cfg)

	app.ApplySubtitlePattern(rawCfg.ClashForAndroid.UiSubtitlePattern)

	loaded.Store(true)

	runtime.GC()

	return nil
}

func LoadDefault() {
	cfg, err := config.Parse([]byte{})
	if err != nil {
		panic(err.Error())
	}

	loaded.Store(false)

	hub.ApplyConfig(cfg)
}
