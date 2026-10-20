package config

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	U "net/url"
	"os"
	P "path"
	"runtime"
	"strconv"
	"strings"
	"time"

	"cfa/native/app"

	mhttp "github.com/metacubex/http"
	"github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/component/dialer"
	clashHttp "github.com/metacubex/mihomo/component/http"
	"github.com/metacubex/mihomo/log"
	RB "github.com/metacubex/mihomo/rules/bundle"
)

type Status struct {
	Action            string   `json:"action"`
	Args              []string `json:"args"`
	Progress          int      `json:"progress"`
	MaxProgress       int      `json:"max"`
	SubUpload         *int64   `json:"subUpload,omitempty"`
	SubDownload       *int64   `json:"subDownload,omitempty"`
	SubTotal          *int64   `json:"subTotal,omitempty"`
	SubExpire         *int64   `json:"subExpire,omitempty"`
	SubUpdateInterval *int64   `json:"subUpdateInterval,omitempty"`
}

type fetchHeader struct {
	SubscriptionUserInfo  string
	ProfileUpdateInterval string
}

// directDialer dials past the core's tunnel. Requests made by the app take the
// tunnel first, so a subscription the raw network blocks can still be fetched
// through the exit the user runs; this dialer is the retry for the other
// direction - a tunnel whose exit is broken while the URL itself is reachable.
var directDialer = dialer.NewDialer()

func openUrl(ctx context.Context, url string) (io.ReadCloser, fetchHeader, error) {
	header := http.Header{"User-Agent": {"ClashMetaForAndroid/" + app.VersionName()}}

	response, err := clashHttp.HttpRequest(ctx, url, http.MethodGet, header, nil)
	if err == nil {
		return fetchBody(response.Header, response.Body)
	}

	tunnelErr := err

	response, err = clashHttp.HttpRequest(ctx, url, http.MethodGet, header, nil, clashHttp.WithDialer(directDialer))
	if err != nil {
		log.Warnln("Fetch %s: through the tunnel: %s; direct: %s", url, tunnelErr.Error(), err.Error())

		return nil, fetchHeader{}, fmt.Errorf("through the tunnel: %s; direct: %s", tunnelErr, err)
	}

	log.Warnln("Fetch %s: the tunnel failed (%s), the direct connection answered", url, tunnelErr.Error())

	return fetchBody(response.Header, response.Body)
}

func fetchBody(header mhttp.Header, body io.ReadCloser) (io.ReadCloser, fetchHeader, error) {
	return body, fetchHeader{
		SubscriptionUserInfo:  header.Get("subscription-userinfo"),
		ProfileUpdateInterval: header.Get("profile-update-interval"),
	}, nil
}

func openContent(url string) (io.ReadCloser, error) {
	return app.OpenContent(url)
}

func fetch(url *U.URL, file string) (fetchHeader, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	var reader io.ReadCloser
	var header fetchHeader
	var err error

	switch url.Scheme {
	case "http", "https":
		reader, header, err = openUrl(ctx, url.String())
	case "content":
		reader, err = openContent(url.String())
	default:
		err = fmt.Errorf("unsupported scheme %s of %s", url.Scheme, url)
	}

	if err != nil {
		return fetchHeader{}, err
	}

	defer reader.Close()

	return header, writeFile(file, reader)
}

func writeFile(file string, reader io.Reader) error {
	_ = os.MkdirAll(P.Dir(file), 0700)

	f, err := os.OpenFile(file, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
	if err != nil {
		return err
	}

	defer f.Close()

	_, err = io.Copy(f, reader)
	if err != nil {
		_ = os.Remove(file)
	}

	return err
}

func parseProfileUpdateInterval(value string) (int64, bool) {
	hours, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	if err != nil {
		return 0, false
	}

	if hours <= 0 {
		return 0, true
	}

	interval := time.Duration(hours) * time.Hour
	if interval < 15*time.Minute {
		interval = 15 * time.Minute
	}

	return int64(interval / time.Millisecond), true
}

func reportSubscriptionInfo(header fetchHeader, reportStatus func(string)) {
	userinfo := header.SubscriptionUserInfo
	updateIntervalHeader := header.ProfileUpdateInterval
	if userinfo == "" && updateIntervalHeader == "" {
		return
	}

	status := Status{
		Action:      "SubscriptionInfo",
		Args:        []string{},
		Progress:    -1,
		MaxProgress: -1,
	}

	if userinfo != "" {
		info := provider.NewSubscriptionInfo(userinfo)
		expire := info.Expire * 1000
		status.SubUpload = &info.Upload
		status.SubDownload = &info.Download
		status.SubTotal = &info.Total
		status.SubExpire = &expire
	}

	if interval, ok := parseProfileUpdateInterval(updateIntervalHeader); ok {
		status.SubUpdateInterval = &interval
	}

	bytes, _ := json.Marshal(&status)
	reportStatus(string(bytes))
}

func FetchAndValid(
	path string,
	url string,
	force bool,
	reportStatus func(string),
) error {
	configPath := P.Join(path, "config.yaml")

	if _, err := os.Stat(configPath); os.IsNotExist(err) || force {
		url, err := U.Parse(url)
		if err != nil {
			return err
		}

		bytes, _ := json.Marshal(&Status{
			Action:      "FetchConfiguration",
			Args:        []string{url.Host},
			Progress:    -1,
			MaxProgress: -1,
		})

		reportStatus(string(bytes))

		header, err := fetch(url, configPath)
		if err != nil {
			return err
		}

		reportSubscriptionInfo(header, reportStatus)
	}

	defer runtime.GC()

	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		return err
	}

	forEachProviders(rawCfg, func(index int, total int, name string, provider map[string]any, prefix string) {
		bytes, _ := json.Marshal(&Status{
			Action:      "FetchProviders",
			Args:        []string{name},
			Progress:    index,
			MaxProgress: total,
		})

		reportStatus(string(bytes))

		u, uok := provider["url"]
		p, pok := provider["path"]

		if !uok || !pok {
			return
		}

		us, uok := u.(string)
		ps, pok := p.(string)

		if !uok || !pok {
			return
		}

		if _, err := os.Stat(ps); err == nil {
			return
		}

		url, err := U.Parse(us)
		if err != nil {
			return
		}

		if prefix == RULES {
			if pib, uok := provider["path-in-bundle"]; uok {
				if pib, uok := pib.(string); uok && pib != "" {
					// actually, we don't need to extract the file here; the core will do it.
					// however, due to historical reasons, CMFA fetches provider content when loading profile,
					// so we maintain consistency with the old behavior.
					if file, err := RB.Open(pib); err == nil {
						defer file.Close()
						if err := writeFile(ps, file); err == nil {
							return
						}
					}
				}
			}
		}

		_, _ = fetch(url, ps)
	})

	bytes, _ := json.Marshal(&Status{
		Action:      "Verifying",
		Args:        []string{},
		Progress:    0xffff,
		MaxProgress: 0xffff,
	})

	reportStatus(string(bytes))

	cfg, err := Parse(rawCfg)
	if err != nil {
		return err
	}

	destroyProviders(cfg)

	return nil
}
