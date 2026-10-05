package probtest

import (
	"strings"
	"testing"
)

// miniTemplate mirrors the shape of the ACL4SSR templates the app ships: a
// handful of rule sources, a mix of select and probed groups, one group
// reached only by a pattern, and a catch all.
const miniTemplate = `[custom]
ruleset=🛑 广告拦截,https://example.com/rules/BanAD.list
ruleset=🚀 节点选择,https://example.com/rules/GFW.list
ruleset=🎯 全球直连,[]GEOIP,CN
ruleset=🐟 漏网之鱼,[]FINAL

custom_proxy_group=🚀 节点选择` + "`" + `select` + "`" + `[]♻️ 自动选择` + "`" + `[]🇭🇰 香港节点` + "`" + `[]DIRECT
custom_proxy_group=♻️ 自动选择` + "`" + `url-test` + "`" + `.*` + "`" + `http://www.gstatic.com/generate_204` + "`" + `300,,50
custom_proxy_group=🇭🇰 香港节点` + "`" + `url-test` + "`" + `(港|HK)` + "`" + `http://www.gstatic.com/generate_204` + "`" + `300,,150
custom_proxy_group=🛑 广告拦截` + "`" + `select` + "`" + `[]REJECT` + "`" + `[]DIRECT
custom_proxy_group=🎯 全球直连` + "`" + `select` + "`" + `[]DIRECT` + "`" + `[]🚀 节点选择
custom_proxy_group=🐟 漏网之鱼` + "`" + `select` + "`" + `[]🚀 节点选择` + "`" + `[]DIRECT
`

// miniFiles is the rule payload miniTemplate resolves to.
var miniFiles = map[string][]byte{
	"BanAD.list": []byte("# a comment to be dropped\nDOMAIN,ads.example.com\n\n; another\nDOMAIN-KEYWORD,adsrv\n"),
	"GFW.list":   []byte("DOMAIN-SUFFIX,blocked.example\n"),
}

func parseMini(t *testing.T) *Template {
	t.Helper()

	tpl, err := ParseTemplate([]byte(miniTemplate), miniFiles)
	if err != nil {
		t.Fatalf("ParseTemplate: %v", err)
	}

	return tpl
}

func keep(names ...string) map[string]struct{} {
	out := make(map[string]struct{}, len(names))
	for _, n := range names {
		out[n] = struct{}{}
	}

	return out
}

func groupNamed(t *testing.T, groups []any, name string) map[string]any {
	t.Helper()

	for _, entry := range groups {
		m, ok := entry.(map[string]any)
		if !ok {
			t.Fatalf("group entry is %T, not a mapping", entry)
		}
		if m["name"] == name {
			return m
		}
	}

	t.Fatalf("no group named %q in %v", name, groups)

	return nil
}

func membersOf(t *testing.T, group map[string]any) []string {
	t.Helper()

	list, ok := group["proxies"].([]any)
	if !ok {
		t.Fatalf("group %v has proxies of type %T", group["name"], group["proxies"])
	}

	out := make([]string, 0, len(list))
	for _, entry := range list {
		s, ok := entry.(string)
		if !ok {
			t.Fatalf("member %v is %T, not a string", entry, entry)
		}
		out = append(out, s)
	}

	return out
}

func ruleStrings(t *testing.T, rules []any) []string {
	t.Helper()

	out := make([]string, 0, len(rules))
	for _, entry := range rules {
		s, ok := entry.(string)
		if !ok {
			t.Fatalf("rule %v is %T, not a string", entry, entry)
		}
		out = append(out, s)
	}

	return out
}

func assertMembers(t *testing.T, group map[string]any, want []string) {
	t.Helper()

	got := membersOf(t, group)
	if len(got) != len(want) {
		t.Fatalf("group %v members = %v, want %v", group["name"], got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("group %v member %d = %q, want %q (all: %v)", group["name"], i, got[i], want[i], got)
		}
	}
}

func TestParseTemplateReadsRulesetsAndGroups(t *testing.T) {
	tpl := parseMini(t)

	if len(tpl.Rulesets) != 4 {
		t.Fatalf("rulesets = %d, want 4", len(tpl.Rulesets))
	}
	if got := tpl.Rulesets[0]; got.Group != "🛑 广告拦截" || got.Source != "https://example.com/rules/BanAD.list" {
		t.Errorf("first ruleset = %+v", got)
	}
	// The inline source carries a comma of its own, so only the first one
	// separates it from its group.
	if got := tpl.Rulesets[2]; got.Group != "🎯 全球直连" || got.Source != "[]GEOIP,CN" {
		t.Errorf("inline ruleset = %+v, want group 🎯 全球直连 and source []GEOIP,CN", got)
	}
	if tpl.Final != "🐟 漏网之鱼" {
		t.Errorf("Final = %q, want 🐟 漏网之鱼", tpl.Final)
	}

	if len(tpl.Groups) != 6 {
		t.Fatalf("groups = %d, want 6", len(tpl.Groups))
	}

	probe := groupNamedByName(t, tpl, "♻️ 自动选择")
	if probe.Type != "url-test" || probe.URL != "http://www.gstatic.com/generate_204" {
		t.Errorf("probed group = %+v", probe)
	}
	if probe.Interval != 300 || probe.Timeout != 0 || probe.Tolerance != 50 {
		t.Errorf("probe settings = interval %d timeout %d tolerance %d, want 300/0/50",
			probe.Interval, probe.Timeout, probe.Tolerance)
	}
	// The template widens the tolerance for the group whose nodes are spread
	// over more than one latitude.
	if hk := groupNamedByName(t, tpl, "🇭🇰 香港节点"); hk.Tolerance != 150 {
		t.Errorf("tolerance = %d, want 150", hk.Tolerance)
	}

	sel := groupNamedByName(t, tpl, "🚀 节点选择")
	if sel.Type != "select" || sel.URL != "" || sel.Interval != 0 {
		t.Errorf("select group should carry no probe settings, got %+v", sel)
	}
	if len(sel.Members) != 3 || sel.Members[0] != "[]♻️ 自动选择" {
		t.Errorf("select members = %v", sel.Members)
	}
}

func groupNamedByName(t *testing.T, tpl *Template, name string) Group {
	t.Helper()

	for _, g := range tpl.Groups {
		if g.Name == name {
			return g
		}
	}

	t.Fatalf("no group named %q", name)

	return Group{}
}

func TestParseTemplateRejectsAReferenceToNothing(t *testing.T) {
	ini := miniTemplate + "custom_proxy_group=坏组`select`[]不存在的组\n"

	if _, err := ParseTemplate([]byte(ini), miniFiles); err == nil {
		t.Fatal("a reference to a group the template does not define was accepted")
	}
}

func TestParseTemplateRejectsADuplicateGroup(t *testing.T) {
	ini := miniTemplate + "custom_proxy_group=♻️ 自动选择`select`.*\n"

	if _, err := ParseTemplate([]byte(ini), miniFiles); err == nil {
		t.Fatal("a repeated group name was accepted")
	}
}

func TestParseTemplateRejectsAnInvalidPattern(t *testing.T) {
	ini := miniTemplate + "custom_proxy_group=坏模式`select`(未闭合\n"

	if _, err := ParseTemplate([]byte(ini), miniFiles); err == nil {
		t.Fatal("a pattern the probe cannot compile was accepted")
	}
}

func TestParseTemplateRejectsAMissingRuleFile(t *testing.T) {
	ini := "[custom]\nruleset=目标组,https://example.com/rules/没有的.list\n" +
		"custom_proxy_group=目标组`select`.*\n"

	if _, err := ParseTemplate([]byte(ini), miniFiles); err == nil {
		t.Fatal("a ruleset whose file was never provided was accepted")
	}
}

func TestParseTemplateRejectsATemplateWithNoCatchAll(t *testing.T) {
	ini := "[custom]\nruleset=目标组,[]GEOIP,CN\n" +
		"custom_proxy_group=目标组`select`.*\n"

	if _, err := ParseTemplate([]byte(ini), miniFiles); err == nil {
		t.Fatal("a template with no []FINAL ruleset was accepted")
	}
}

func TestRenderClassifiesNodesByPattern(t *testing.T) {
	tpl := parseMini(t)

	groups, rules, providers, err := tpl.Render(keep("HK-01", "JP-01", "US-01", "港-02"))
	if err != nil {
		t.Fatalf("Render: %v", err)
	}

	// Every surviving node sits in the group that probes them all.
	assertMembers(t, groupNamed(t, groups, "♻️ 自动选择"), []string{"HK-01", "JP-01", "US-01", "港-02"})
	// Only the two the pattern claims, in the order the surviving set was
	// sorted rather than the order they were kept in.
	assertMembers(t, groupNamed(t, groups, "🇭🇰 香港节点"), []string{"HK-01", "港-02"})
	// The select group leads with the template's own first choice.
	assertMembers(t, groupNamed(t, groups, "🚀 节点选择"),
		[]string{"♻️ 自动选择", "🇭🇰 香港节点", "DIRECT"})

	if len(rules) != 4 {
		t.Fatalf("rules = %v, want 4", rules)
	}
	want := []string{
		"RULE-SET,BanAD.list,🛑 广告拦截",
		"RULE-SET,GFW.list,🚀 节点选择",
		"GEOIP,CN,🎯 全球直连",
		"MATCH,🐟 漏网之鱼",
	}
	got := ruleStrings(t, rules)
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("rule %d = %q, want %q", i, got[i], want[i])
		}
	}

	if len(providers) != 2 {
		t.Fatalf("providers = %v, want BanAD.list and GFW.list", providers)
	}
	for _, name := range []string{"BanAD.list", "GFW.list"} {
		p, ok := providers[name].(map[string]any)
		if !ok {
			t.Fatalf("provider %q is %T", name, providers[name])
		}
		if p["type"] != "inline" || p["behavior"] != "classical" {
			t.Errorf("provider %q = type %v behavior %v, want inline/classical", name, p["type"], p["behavior"])
		}
		payload, ok := p["payload"].([]string)
		if !ok {
			t.Fatalf("provider %q payload is %T", name, p["payload"])
		}
		if len(payload) == 0 {
			t.Errorf("provider %q holds no rule", name)
		}
		for _, line := range payload {
			if strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
				t.Errorf("provider %q still carries a comment: %q", name, line)
			}
		}
	}

	// The advertising rules reach the payload in order, minus every comment.
	advert := providers["BanAD.list"].(map[string]any)["payload"].([]string)
	wantAdvert := []string{"DOMAIN,ads.example.com", "DOMAIN-KEYWORD,adsrv"}
	if strings.Join(advert, ",") != strings.Join(wantAdvert, ",") {
		t.Errorf("BanAD payload = %v, want %v", advert, wantAdvert)
	}
}

func TestRenderDropsAGroupThatMatchedNothing(t *testing.T) {
	tpl := parseMini(t)

	// No node matches (港|HK), so the Hong Kong group reaches nothing.
	groups, rules, _, err := tpl.Render(keep("JP-01", "US-01"))
	if err != nil {
		t.Fatalf("Render: %v", err)
	}

	for _, entry := range groups {
		m := entry.(map[string]any)
		if m["name"] == "🇭🇰 香港节点" {
			t.Fatalf("a group that matched no node was published: %v", m)
		}
	}

	// The reference to the group that went is stripped, while the rest of the
	// member list keeps its place.
	assertMembers(t, groupNamed(t, groups, "🚀 节点选择"), []string{"♻️ 自动选择", "DIRECT"})
	// A group that lost one member still publishes, and a rule pointing at it
	// still lands somewhere real.
	assertMembers(t, groupNamed(t, groups, "🛑 广告拦截"), []string{"REJECT", "DIRECT"})
	if len(rules) != 4 {
		t.Errorf("rules = %v, want all four still present", rules)
	}
}

func TestRenderSendsANowhereRuleToTheCatchAll(t *testing.T) {
	// A template whose rule source routes into a group reached only by a
	// pattern, so losing every node of that kind moves the traffic rather than
	// leaving a rule that points at nothing.
	ini := "[custom]\n" +
		"ruleset=🇭🇰 分流,https://example.com/rules/GFW.list\n" +
		"ruleset=🐟 漏网之鱼,[]FINAL\n" +
		"custom_proxy_group=🇭🇰 分流`select`(港|HK)\n" +
		"custom_proxy_group=🐟 漏网之鱼`select`[]DIRECT\n"

	tpl, err := ParseTemplate([]byte(ini), miniFiles)
	if err != nil {
		t.Fatalf("ParseTemplate: %v", err)
	}

	_, rules, _, err := tpl.Render(keep("US-01"))
	if err != nil {
		t.Fatalf("Render: %v", err)
	}

	got := ruleStrings(t, rules)
	want := []string{
		"RULE-SET,GFW.list,🐟 漏网之鱼",
		"MATCH,🐟 漏网之鱼",
	}
	if len(got) != len(want) {
		t.Fatalf("rules = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("rule %d = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestRenderRefusesToPublishWithoutACatchAll(t *testing.T) {
	// 🐟 漏网之鱼 is reached only through the group that just emptied, so
	// nothing is left to receive the traffic a dropped group would have sent.
	ini := "[custom]\n" +
		"ruleset=🇭🇰 分流,https://example.com/rules/GFW.list\n" +
		"ruleset=🐟 漏网之鱼,[]FINAL\n" +
		"custom_proxy_group=🇭🇰 分流`select`(港|HK)\n" +
		"custom_proxy_group=中间组`select`[]🇭🇰 分流\n" +
		"custom_proxy_group=🐟 漏网之鱼`select`[]中间组\n"

	tpl, err := ParseTemplate([]byte(ini), miniFiles)
	if err != nil {
		t.Fatalf("ParseTemplate: %v", err)
	}

	if _, _, _, err := tpl.Render(keep("US-01")); err == nil {
		t.Fatal("a template whose catch all had emptied was published")
	}
}

func TestRenderKeepsTheTemplateRuleOrder(t *testing.T) {
	// Rule evaluation order is the order the sources were written in: an
	// earlier rule has to keep the chance to match before a later one.
	ini := "[custom]\n" +
		"ruleset=甲组,https://example.com/rules/GFW.list\n" +
		"ruleset=乙组,[]GEOIP,CN\n" +
		"ruleset=丙组,[]FINAL\n" +
		"custom_proxy_group=甲组`select`.*\n" +
		"custom_proxy_group=乙组`select`[]DIRECT\n" +
		"custom_proxy_group=丙组`select`[]DIRECT\n"

	tpl, err := ParseTemplate([]byte(ini), miniFiles)
	if err != nil {
		t.Fatalf("ParseTemplate: %v", err)
	}

	_, rules, _, err := tpl.Render(keep("US-01"))
	if err != nil {
		t.Fatalf("Render: %v", err)
	}

	want := []string{"RULE-SET,GFW.list,甲组", "GEOIP,CN,乙组", "MATCH,丙组"}
	got := ruleStrings(t, rules)
	if strings.Join(got, " | ") != strings.Join(want, " | ") {
		t.Fatalf("rules = %v, want %v", got, want)
	}
}

func TestRenderStripsADroppedGroupFromEveryReference(t *testing.T) {
	// Losing the Hong Kong group empties the group that existed only to point
	// at it, and that in turn has to come out of the ones pointing at it.
	ini := "[custom]\n" +
		"ruleset=顶层,[]FINAL\n" +
		"custom_proxy_group=🇭🇰 香港节点`select`(港|HK)\n" +
		"custom_proxy_group=中层`select`[]🇭🇰 香港节点\n" +
		"custom_proxy_group=顶层`select`[]中层`[]DIRECT\n" +
		"custom_proxy_group=旁组`select`.*\n"

	tpl, err := ParseTemplate([]byte(ini), miniFiles)
	if err != nil {
		t.Fatalf("ParseTemplate: %v", err)
	}

	groups, _, _, err := tpl.Render(keep("US-01"))
	if err != nil {
		t.Fatalf("Render: %v", err)
	}

	for _, name := range []string{"🇭🇰 香港节点", "中层"} {
		for _, entry := range groups {
			if entry.(map[string]any)["name"] == name {
				t.Errorf("group %q should have been dropped", name)
			}
		}
	}
	assertMembers(t, groupNamed(t, groups, "顶层"), []string{"DIRECT"})
}
