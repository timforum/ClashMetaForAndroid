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
// surviving nodes.
//
// A round given a template takes its group and rule layout from the template;
// a round given none keeps the fixed built in layout. Either way the layout is
// rebuilt rather than inherited, so the published configuration routes the way
// the screening intends no matter what the source subscription looked like.
func rewrite(root map[string]any, keep map[string]struct{}, tpl *Template) ([]byte, error) {
	out, err := narrowToSurvivors(root, keep)
	if err != nil {
		return nil, err
	}

	if tpl != nil {
		return rewriteFromTemplate(out, keep, tpl)
	}

	return rewriteFromFixedLayout(out, keep)
}

// narrowToSurvivors copies the source configuration, keeps only the surviving
// nodes in it, and strips what a published document must not carry.
func narrowToSurvivors(root map[string]any, keep map[string]struct{}) (map[string]any, error) {
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

	return out, nil
}

// rewriteFromFixedLayout applies the built in four group layout and rule list.
func rewriteFromFixedLayout(out map[string]any, keep map[string]struct{}) ([]byte, error) {
	// Everything a published rule is allowed to point at. A rule naming
	// anything else named a group in the source subscription, which is not
	// being published, so it is redirected to Auto rather than left dangling.
	allowed := make(map[string]bool, len(builtinTargets)+4)
	for name := range builtinTargets {
		allowed[name] = true
	}
	for _, name := range policyGroupNames {
		allowed[name] = true
	}

	out["proxy-groups"] = policyGroups(keep)

	out["rules"] = rewriteRules()

	if subRules, ok := out["sub-rules"]; ok {
		out["sub-rules"] = rewriteSubRules(subRules, allowed)
	}

	return yaml.Marshal(out)
}

// rewriteFromTemplate applies a parsed template in place of the built in
// layout: the groups the surviving nodes fill, the rule providers carrying the
// rule files, and the rules that join them.
//
// sub-rules go rather than being rewritten. They belong to the source
// subscription, which is not what the published configuration routes, and the
// template defines none of its own, so keeping them would publish a structure
// no rule reads.
func rewriteFromTemplate(out map[string]any, keep map[string]struct{}, tpl *Template) ([]byte, error) {
	groups, rules, providers, err := tpl.Render(keep)
	if err != nil {
		return nil, err
	}

	// A published rule may only name something the document actually holds:
	// mihomo's built in targets, or a group the template wrote. A group the
	// template dropped is absent from the output, so naming it would dangle.
	allowed := make(map[string]bool, len(builtinTargets)+len(groups))
	for name := range builtinTargets {
		allowed[name] = true
	}
	for _, entry := range groups {
		if m, ok := entry.(map[string]any); ok {
			if name, ok := m["name"].(string); ok {
				allowed[name] = true
			}
		}
	}

	out["proxy-groups"] = groups
	out["rules"] = rules
	if len(providers) > 0 {
		out["rule-providers"] = providers
	}
	delete(out, "sub-rules")

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

// The published configuration always carries these four groups, in this order.
//
// A subscription's own groups are not published. They decide where its traffic
// goes, and a panel is free to call a group "Auto" while making it a plain
// select, or to point its rules at a group a reader never sees. Inheriting
// that produces a feed where changing a group in the app appears to do nothing,
// because the rules reach some other group entirely - which reads as a broken
// app rather than as a subscription the reader does not control. A fixed
// layout is the one thing every reader can reason about before opening it.
const (
	policyAuto       = "Auto"
	policySelect     = "Select"
	policyLoad       = "LoadBalance"
	policyFallback   = "Fallback"
	loadBalanceRound = "round-robin"
)

// policyGroupNames is the published layout, in the order it is written out.
var policyGroupNames = []string{policyAuto, policySelect, policyLoad, policyFallback}

// publishedRules is the fixed rule list of the published configuration.
//
// Select, not Auto, is where the rules send traffic: Select is the group the
// reader actually moves in Clash Verge, so choosing Auto / LoadBalance /
// Fallback there is what visibly decides where the connections go. Auto stays
// in the configuration as the url-test group the other two probe against, but
// the rules no longer bypass the reader's choice by matching onto it.
var publishedRules = []string{
	"GEOIP,CN,DIRECT",
	"GEOIP,LAN,DIRECT",
	"GEOIP,PRIVATE,DIRECT",
	"DOMAIN-SUFFIX,youtube.com,Select",
	"DOMAIN-SUFFIX,youtube-nocookie.com,Select",
	"DOMAIN-SUFFIX,googlevideo.com,Select",
	"DOMAIN-SUFFIX,ytimg.com,Select",
	"DOMAIN-SUFFIX,gvt1.com,Select",
	"DOMAIN-SUFFIX,gvt2.com,Select",
	"MATCH,Select",
}

// policyGroups builds the published groups over every surviving node, so all of
// them cover the whole screened set no matter how few nodes there are.
func policyGroups(keep map[string]struct{}) []any {
	members := make([]any, 0, len(keep))
	for name := range keep {
		members = append(members, name)
	}
	sortAny(members)

	// Auto leads because it is where the published rules send traffic, so it is
	// the group a reader sees doing the work.
	group := func(name, kind string, extra ...any) map[string]any {
		entry := map[string]any{
			"name":    name,
			"type":    kind,
			"proxies": members,
		}
		for i := 0; i+1 < len(extra); i += 2 {
			entry[extra[i].(string)] = extra[i+1]
		}

		return entry
	}

	return []any{
		// Picks whichever surviving node answers fastest, and re-picks on its
		// own when that node stops answering.
		group(policyAuto, "url-test", "url", policyAuto),
		// The only group a reader can move by hand.
		group(policySelect, "select"),
		// Spreads connections over the surviving nodes instead of funnelling
		// everything through the fastest one.
		group(policyLoad, "load-balance", "strategy", loadBalanceRound),
		// Holds the first node that works and only moves off it once it stops.
		group(policyFallback, "fallback", "url", policyAuto),
	}
}

// rewriteRules returns the fixed published rule list.
//
// The source subscription's rules are discarded rather than rewritten. They
// name groups that are not published and pin the reader out of the choice they
// just made in Clash Verge; a fixed list that ends in MATCH,Select is the one
// layout where moving a group is what visibly does the work.
func rewriteRules() []any {
	out := make([]any, len(publishedRules))
	for i, rule := range publishedRules {
		out[i] = rule
	}

	return out
}

// ruleNoResolve is a flag some rules end with. It is not a target, and reading
// it as one loses every such rule.
const ruleNoResolve = "no-resolve"

// ruleTarget splits "TYPE,arg...,TARGET[,no-resolve]" and reports the target,
// whether the rule is a catch-all, and whether the flag was present.
func ruleTarget(rule string) (target string, isMatch bool, noResolve bool, ok bool) {
	parts := strings.Split(rule, ",")
	if len(parts) < 2 {
		return "", false, false, false
	}

	if strings.EqualFold(strings.TrimSpace(parts[len(parts)-1]), ruleNoResolve) {
		noResolve = true
		parts = parts[:len(parts)-1]
	}

	if len(parts) < 2 {
		return "", false, false, false
	}

	target = strings.TrimSpace(parts[len(parts)-1])
	if target == "" {
		return "", false, false, false
	}

	head := strings.ToUpper(strings.TrimSpace(parts[0]))

	return target, head == "MATCH" || head == "NO-MATCH", noResolve, true
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
		if target, _, _, ok := ruleTarget(rule); ok && alive[target] {
			out = append(out, rule)
		}
	}

	return out
}

// requiredGroup is the group a published document must contain for its rules
// to reach anywhere: the built in Auto for the fixed layout, the template's
// catch all for a templated one.
func requiredGroup(tpl *Template) string {
	if tpl == nil {
		return policyAuto
	}

	return tpl.Final
}

// verify checks a published document against the set of nodes it should hold
// and the one group that has to be there for its rules to reach anywhere.
//
// required names that group. The built in layout sends its rules to Auto; a
// template sends them to whatever its []FINAL ruleset names, so requiring Auto
// of a templated document would reject a correct one.
func verify(out []byte, keep map[string]struct{}, required string) error {
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

	// The rules have to reach a group that exists. Auto is where the built in
	// rules send traffic and a template's []FINAL group is where a templated
	// one sends it; a document without that group loads and then matches
	// everything onto a target that is not there.
	if required != "" && !groupNames[required] {
		return fmt.Errorf("published configuration has no %q group", required)
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
			target, isMatch, _, ok := ruleTarget(rule)
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
