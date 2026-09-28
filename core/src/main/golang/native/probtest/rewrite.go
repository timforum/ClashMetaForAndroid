package probtest

import (
	"fmt"
	"strings"

	"gopkg.in/yaml.v3"
)

// builtinTargets always exist inside mihomo regardless of the subscription,
// so a group member or rule target can name them safely.
var builtinTargets = map[string]bool{
	"DIRECT":      true,
	"REJECT":      true,
	"REJECT-DROP": true,
	"PASS":        true,
	"COMPATIBLE":  true,
}

// publishedOnlyKeys are credentials or machine specific settings that must not
// travel with a configuration pushed to a public repository.
var publishedOnlyKeys = []string{
	"secret",
	"authentication",
	"external-controller",
	"external-controller-tls",
	"external-controller-unix",
	"interface-name",
	"routing-mark",
}

// rewrite builds a complete, loadable Clash configuration that keeps only the
// surviving nodes. Group membership and rule targets are recomputed so no
// reference points at a node that was removed.
func rewrite(root map[string]any, keep map[string]struct{}) ([]byte, error) {
	alive := make(map[string]bool, len(keep)+len(builtinTargets))
	for name := range keep {
		alive[name] = true
	}
	for name := range builtinTargets {
		alive[name] = true
	}

	proxies, err := filterProxies(root["proxies"], keep)
	if err != nil {
		return nil, err
	}
	if len(proxies) != len(keep) {
		return nil, fmt.Errorf("kept %d nodes but configuration holds %d", len(keep), len(proxies))
	}

	out := make(map[string]any, len(root))
	for k, v := range root {
		out[k] = v
	}
	out["proxies"] = proxies
	// Every node is inlined now, so a provider would reintroduce untested ones.
	delete(out, "proxy-providers")
	for _, k := range publishedOnlyKeys {
		delete(out, k)
	}

	groups, firstGroup := rewriteGroups(root["proxy-groups"], alive, keep)
	out["proxy-groups"] = groups

	out["rules"] = rewriteRules(root["rules"], alive, firstGroup)

	if subRules, ok := root["sub-rules"]; ok {
		out["sub-rules"] = rewriteSubRules(subRules, alive)
	}

	return yaml.Marshal(out)
}

// ProxiesDocument renders a document that holds nothing but the proxies of an
// already rewritten configuration.
//
// The subscription worker this feeds (/internal/freeclashnode) rejects any
// document whose top level is not exactly {proxies: [...]}, so the full
// configuration rewrite() produces cannot be uploaded to it as is.
func ProxiesDocument(configuration []byte) ([]byte, error) {
	var root map[string]any
	if err := yaml.Unmarshal(configuration, &root); err != nil {
		return nil, fmt.Errorf("cannot read configuration: %w", err)
	}

	proxies, ok := root["proxies"]
	if !ok {
		return nil, fmt.Errorf("configuration holds no proxies")
	}

	list, ok := proxies.([]any)
	if !ok || len(list) == 0 {
		return nil, fmt.Errorf("configuration holds no usable proxies")
	}

	return yaml.Marshal(map[string]any{"proxies": list})
}

func filterProxies(raw any, keep map[string]struct{}) ([]any, error) {
	list, ok := raw.([]any)
	if !ok {
		return nil, fmt.Errorf("`proxies` is %T, expected a list", raw)
	}

	out := make([]any, 0, len(keep))
	for _, entry := range list {
		name := nodeName(entry, len(out))
		if _, ok := keep[name]; ok {
			out = append(out, entry)
		}
	}

	return out, nil
}

type groupInfo struct {
	entry   map[string]any
	name    string
	members []any
}

// rewriteGroups drops groups that lost every member, narrows the remaining
// members to surviving targets and returns the first usable group name.
func rewriteGroups(raw any, alive map[string]bool, keep map[string]struct{}) ([]any, string) {
	list, ok := raw.([]any)
	if !ok {
		// No groups at all: synthesise a single selector over the survivors.
		return synthesizeGroups(keep), firstOrDefault(keep, "")
	}

	infos := make([]*groupInfo, 0, len(list))
	for _, entry := range list {
		m, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		name, _ := m["name"].(string)
		if name == "" {
			continue
		}
		infos = append(infos, &groupInfo{entry: m, name: name, members: rawList(m["proxies"])})
	}

	// A group referencing another group can only be decided once that group
	// is known to survive, so iterate until the alive set stops growing.
	for iter := 0; iter <= len(infos); iter++ {
		grew := false

		for _, gi := range infos {
			if alive[gi.name] {
				continue
			}
			for _, member := range gi.members {
				if n, _ := member.(string); alive[n] {
					alive[gi.name] = true
					grew = true
					break
				}
			}
		}

		if !grew {
			break
		}
	}

	out := make([]any, 0, len(infos))
	first := ""

	for _, gi := range infos {
		if !alive[gi.name] {
			continue
		}

		members := make([]any, 0, len(gi.members))
		for _, member := range gi.members {
			if n, ok := member.(string); ok && alive[n] {
				members = append(members, member)
			}
		}
		if len(members) == 0 {
			delete(alive, gi.name)
			continue
		}

		if len(members) != len(gi.members) {
			entry := make(map[string]any, len(gi.entry))
			for k, v := range gi.entry {
				entry[k] = v
			}
			entry["proxies"] = members

			// `now` must name a member that still exists.
			if now, hasNow := entry["now"]; hasNow {
				if s, _ := now.(string); !containsString(members, s) {
					delete(entry, "now")
				}
			}

			gi.entry = entry
		}

		if first == "" {
			first = gi.name
		}

		out = append(out, gi.entry)
	}

	if len(out) == 0 {
		return synthesizeGroups(keep), firstOrDefault(keep, "")
	}

	return out, first
}

func synthesizeGroups(keep map[string]struct{}) []any {
	members := make([]any, 0, len(keep))
	for name := range keep {
		members = append(members, name)
	}
	sortAny(members)

	return []any{map[string]any{
		"name":    "PROXY",
		"type":    "select",
		"proxies": members,
	}}
}

// rewriteRules keeps a rule only when its target still resolves, and guarantees
// a MATCH rule so the published configuration can actually route traffic.
func rewriteRules(raw any, alive map[string]bool, firstGroup string) []any {
	list, ok := raw.([]any)
	if !ok {
		return []any{fmt.Sprintf("MATCH,%s", firstGroup)}
	}

	out := make([]any, 0, len(list))
	hasMatch := false

	for _, entry := range list {
		rule, isStr := entry.(string)
		if !isStr {
			continue
		}

		target, isMatch, ok := ruleTarget(rule)
		if !ok || !alive[target] {
			continue
		}

		if isMatch {
			hasMatch = true
		}

		out = append(out, rule)
	}

	if !hasMatch {
		out = append(out, fmt.Sprintf("MATCH,%s", firstGroup))
	}

	return out
}

// ruleTarget splits "TYPE,arg...,TARGET" and reports the final field.
func ruleTarget(rule string) (target string, isMatch bool, ok bool) {
	parts := strings.Split(rule, ",")
	if len(parts) < 2 {
		return "", false, false
	}

	target = strings.TrimSpace(parts[len(parts)-1])
	if target == "" {
		return "", false, false
	}

	head := strings.ToUpper(strings.TrimSpace(parts[0]))

	return target, head == "MATCH" || head == "NO-MATCH", true
}

func rewriteSubRules(raw any, alive map[string]bool) any {
	// sub-rules values are lists of rules; drop the ones whose target died so
	// a dangling reference cannot break the load.
	rules, ok := raw.([]any)
	if !ok {
		return raw
	}

	out := make([]any, 0, len(rules))
	for _, entry := range rules {
		rule, isStr := entry.(string)
		if !isStr {
			continue
		}
		if target, _, ok := ruleTarget(rule); ok && alive[target] {
			out = append(out, rule)
		}
	}

	return out
}

func verify(out []byte, keep map[string]struct{}) error {
	root, err := decodeRoot(out)
	if err != nil {
		return err
	}

	entries, err := collectEntries(root)
	if err != nil {
		return err
	}

	if len(entries) != len(keep) {
		return fmt.Errorf("expected %d nodes, got %d", len(keep), len(entries))
	}

	names := make(map[string]struct{}, len(keep))
	for _, entry := range entries {
		name := nodeName(entry, len(names))
		if _, ok := keep[name]; !ok {
			return fmt.Errorf("unexpected node %q", name)
		}
		if _, dup := names[name]; dup {
			return fmt.Errorf("duplicate node %q", name)
		}
		names[name] = struct{}{}

		m, ok := entry.(map[string]any)
		if !ok {
			return fmt.Errorf("node entry is not a mapping")
		}
		if _, err := parseSafe(m); err != nil {
			return fmt.Errorf("node %q does not parse: %w", name, err)
		}
	}

	alive := make(map[string]bool, len(keep)+len(builtinTargets))
	for name := range keep {
		alive[name] = true
	}
	for name := range builtinTargets {
		alive[name] = true
	}

	groupNames := map[string]bool{}
	if groups, ok := root["proxy-groups"].([]any); ok {
		for _, entry := range groups {
			m, ok := entry.(map[string]any)
			if !ok {
				return fmt.Errorf("group entry is not a mapping")
			}
			name, _ := m["name"].(string)
			if name == "" {
				return fmt.Errorf("group without a name")
			}
			if groupNames[name] {
				return fmt.Errorf("duplicate group %q", name)
			}
			groupNames[name] = true
		}
	}

	// A group that made it into the published document is a valid target in
	// its own right; only references to things absent from either set dangle.
	for name := range groupNames {
		alive[name] = true
	}

	if groups, ok := root["proxy-groups"].([]any); ok {
		for _, entry := range groups {
			m, ok := entry.(map[string]any)
			if !ok {
				continue
			}
			members, _ := m["proxies"].([]any)
			if len(members) == 0 {
				return fmt.Errorf("group %v has no members", m["name"])
			}
			for _, member := range members {
				s, _ := member.(string)
				if !alive[s] {
					return fmt.Errorf("group %v references removed target %q", m["name"], s)
				}
			}
		}
	}

	if rules, ok := root["rules"].([]any); ok {
		hasMatch := false
		for _, entry := range rules {
			rule, isStr := entry.(string)
			if !isStr {
				continue
			}
			target, isMatch, ok := ruleTarget(rule)
			if !ok {
				return fmt.Errorf("malformed rule %q", rule)
			}
			if !alive[target] {
				return fmt.Errorf("rule %q targets removed %q", rule, target)
			}
			if isMatch {
				hasMatch = true
			}
		}
		if !hasMatch {
			return fmt.Errorf("no MATCH rule in published configuration")
		}
	}

	return nil
}

func rawList(v any) []any {
	switch list := v.(type) {
	case []any:
		return list
	case nil:
		return nil
	default:
		return nil
	}
}

func containsString(list []any, want string) bool {
	for _, v := range list {
		if s, ok := v.(string); ok && s == want {
			return true
		}
	}
	return false
}

func firstOrDefault(keep map[string]struct{}, fallback string) string {
	if fallback != "" {
		return fallback
	}
	for name := range keep {
		return name
	}
	return "PROXY"
}

func sortAny(list []any) {
	for i := 1; i < len(list); i++ {
		for j := i; j > 0 && lessAny(list[j], list[j-1]); j-- {
			list[j], list[j-1] = list[j-1], list[j]
		}
	}
}

func lessAny(a, b any) bool {
	as, _ := a.(string)
	bs, _ := b.(string)
	return as < bs
}
