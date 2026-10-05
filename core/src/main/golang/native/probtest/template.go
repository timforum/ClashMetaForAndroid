package probtest

import (
	"fmt"
	"path"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Template is a parsed subconverter style .ini template: the rule sources in
// evaluation order, the proxy groups those rules route into, and the rule files
// the sources resolve to.
//
// The template describes a routing policy, never a node list. Nodes come from
// the candidates that survived screening; the template only decides which of
// them belong in which group and where each rule set sends its traffic.
type Template struct {
	// Rulesets is in template order, which is rule evaluation order.
	Rulesets []Ruleset
	// Groups is in template order, which is the order a reader meets them in.
	Groups []Group
	// Final is the group the []FINAL ruleset points at, and the target any
	// rule whose own group dropped receives as a fallback.
	Final string
	// RuleBase is the optional clash_rule_base URL of the template. A template
	// with no rule base of its own is applied to the candidate subscription,
	// so this is recorded but not required.
	RuleBase string

	// payload maps a rule file name to its comment free lines. Keys are the
	// last path element of the URL a ruleset named, so the same file named
	// through two URLs is read once.
	payload map[string][]string

	// patterns holds each group's members compiled, in member order, with a
	// nil entry wherever the member is a reference rather than a pattern.
	// Compiled once by validate so a round never has to report a syntax the
	// parse step should already have caught.
	patterns map[string][]*regexp.Regexp
}

// Ruleset is one ruleset= entry.
type Ruleset struct {
	// Group is the proxy group this source routes into.
	Group string
	// Source is either a URL naming a classical rule file, or an inline
	// expression written as []FINAL, []GEOIP,CN and the like.
	Source string
}

// Group is one custom_proxy_group= entry.
type Group struct {
	Name      string
	Type      string
	Members   []string
	URL       string
	Interval  int
	Timeout   int
	Tolerance int
}

// inlinePrefix marks a ruleset source as an expression that needs no file.
const inlinePrefix = "[]"

// ParseTemplate reads a .ini template together with the rule files its
// sources resolve to. files is keyed by file name (the last path element of
// the URL), which is what the template directory stores.
func ParseTemplate(ini []byte, files map[string][]byte) (*Template, error) {
	t := &Template{payload: map[string][]string{}}

	for i, raw := range strings.Split(string(ini), "\n") {
		line := strings.TrimSpace(strings.TrimSuffix(raw, "\r"))
		if line == "" || strings.HasPrefix(line, ";") || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.HasPrefix(line, "[") {
			// Section headers carry no data of their own.
			continue
		}

		key, value, ok := strings.Cut(line, "=")
		if !ok {
			return nil, fmt.Errorf("line %d: expected key=value: %q", i+1, line)
		}

		switch strings.ToLower(strings.TrimSpace(key)) {
		case "ruleset":
			rs, err := parseRuleset(value)
			if err != nil {
				return nil, fmt.Errorf("line %d: %w", i+1, err)
			}
			t.Rulesets = append(t.Rulesets, rs)
		case "custom_proxy_group":
			g, err := parseGroup(value)
			if err != nil {
				return nil, fmt.Errorf("line %d: %w", i+1, err)
			}
			t.Groups = append(t.Groups, g)
		case "clash_rule_base":
			t.RuleBase = strings.TrimSpace(value)
		case "enable_rule_generator", "overwrite_original_rules":
			// These govern subconverter, which has no say here: this engine
			// always writes a fresh rule list.
		default:
			// Unknown keys are ignored so a template can carry notes for other
			// generators without becoming unreadable to this one.
		}
	}

	if err := t.validate(); err != nil {
		return nil, err
	}
	if err := t.loadPayloads(files); err != nil {
		return nil, err
	}

	return t, nil
}

// validate checks the template against itself: that its groups are unique,
// that every name it mentions exists, that its patterns compile, and that it
// routes somewhere unmatched traffic can go.
//
// All of it happens before any node is known, so a template that cannot work
// fails once, loudly, rather than being discovered part way through a round.
func (t *Template) validate() error {
	if len(t.Rulesets) == 0 {
		return fmt.Errorf("template holds no ruleset")
	}
	if len(t.Groups) == 0 {
		return fmt.Errorf("template holds no proxy group")
	}

	seen := make(map[string]bool, len(t.Groups))
	for _, g := range t.Groups {
		if g.Name == "" {
			return fmt.Errorf("proxy group without a name")
		}
		if seen[g.Name] {
			return fmt.Errorf("duplicate proxy group %q", g.Name)
		}
		seen[g.Name] = true
	}

	names := make([]string, 0, len(seen))
	for name := range seen {
		names = append(names, name)
	}

	// A ruleset and a group reference both have to land on something the
	// template itself defines, or on a target mihomo always provides. Rejecting
	// an unknown name here keeps the generated document free of references that
	// would otherwise be quietly dropped later.
	for _, rs := range t.Rulesets {
		if !seen[rs.Group] && !builtinTargets[rs.Group] {
			return fmt.Errorf("ruleset routes into unknown group %q", rs.Group)
		}
	}
	for _, g := range t.Groups {
		for _, member := range g.Members {
			ref, ok := memberReference(member)
			if !ok {
				continue
			}
			if !seen[ref] && !builtinTargets[ref] {
				return fmt.Errorf("group %q references unknown group %q", g.Name, ref)
			}
		}
	}

	// Patterns are compiled once so a later round cannot discover a syntax a
	// previous round accepted. A pattern mihomo's RE2 dialect cannot read would
	// otherwise silently classify nothing.
	t.patterns = make(map[string][]*regexp.Regexp, len(t.Groups))
	for _, g := range t.Groups {
		compiled := make([]*regexp.Regexp, len(g.Members))
		for i, member := range g.Members {
			if _, ok := memberReference(member); ok {
				continue
			}
			re, err := regexp.Compile(member)
			if err != nil {
				return fmt.Errorf("group %q has an invalid pattern %q: %w", g.Name, member, err)
			}
			compiled[i] = re
		}
		t.patterns[g.Name] = compiled
	}

	found := ""
	for _, rs := range t.Rulesets {
		if expr, ok := rs.inlineExpression(); ok && strings.EqualFold(expr, "FINAL") {
			found = rs.Group
			break
		}
	}
	if found == "" {
		return fmt.Errorf("template holds no []FINAL ruleset")
	}
	t.Final = found

	return nil
}

// loadPayloads reads the comment free lines of every rule file a ruleset names.
func (t *Template) loadPayloads(files map[string][]byte) error {
	loaded := make(map[string]string)

	for _, rs := range t.Rulesets {
		if rs.isInline() {
			continue
		}

		name := ruleFileName(rs.Source)
		if previous, ok := loaded[name]; ok {
			if previous != rs.Source {
				return fmt.Errorf("rule files %q and %q collide on the name %q", previous, rs.Source, name)
			}
			continue
		}
		loaded[name] = rs.Source

		content, ok := files[name]
		if !ok {
			return fmt.Errorf("ruleset names the file %q, which was not provided", name)
		}

		lines := ruleLines(string(content))
		if len(lines) == 0 {
			return fmt.Errorf("rule file %q holds no rule", name)
		}
		t.payload[name] = lines
	}

	return nil
}

// isInline reports whether the source is an expression rather than a file.
func (r Ruleset) isInline() bool {
	return strings.HasPrefix(r.Source, inlinePrefix)
}

// inlineExpression strips the [] marker from an inline source. The ok result
// distinguishes an inline source from a URL, so a URL beginning with brackets
// is never mistaken for one.
func (r Ruleset) inlineExpression() (string, bool) {
	if !r.isInline() {
		return "", false
	}
	return strings.TrimPrefix(r.Source, inlinePrefix), true
}

// parseRuleset splits "group,source". The split is on the first comma only
// because an inline source carries commas of its own: []GEOIP,CN is one value.
func parseRuleset(value string) (Ruleset, error) {
	value = strings.TrimSpace(value)
	group, source, ok := strings.Cut(value, ",")
	if !ok {
		return Ruleset{}, fmt.Errorf("ruleset needs a group and a source: %q", value)
	}

	rs := Ruleset{Group: strings.TrimSpace(group), Source: strings.TrimSpace(source)}
	if rs.Group == "" {
		return Ruleset{}, fmt.Errorf("ruleset without a group: %q", value)
	}
	if rs.Source == "" {
		return Ruleset{}, fmt.Errorf("ruleset %q without a source", rs.Group)
	}

	return rs, nil
}

// parseGroup splits one custom_proxy_group line. Members are separated by
// backticks, the type decides whether a test URL and probe settings follow, and
// settings are "interval[,timeout][,tolerance]".
func parseGroup(value string) (Group, error) {
	fields := strings.Split(value, "`")
	if len(fields) < 3 {
		return Group{}, fmt.Errorf("proxy group needs a name, a type and a member: %q", value)
	}

	g := Group{
		Name:    strings.TrimSpace(fields[0]),
		Type:    strings.ToLower(strings.TrimSpace(fields[1])),
		Members: splitMembers(fields[2:]),
	}
	if g.Name == "" {
		return Group{}, fmt.Errorf("proxy group without a name: %q", value)
	}
	if len(g.Members) == 0 {
		return Group{}, fmt.Errorf("group %q holds no member", g.Name)
	}

	if g.Type == "select" {
		return g, nil
	}
	if g.Type != "url-test" && g.Type != "fallback" && g.Type != "load-balance" {
		return Group{}, fmt.Errorf("group %q has unsupported type %q", g.Name, g.Type)
	}
	if len(fields) < 5 {
		return Group{}, fmt.Errorf("group %q of type %q needs a test URL and probe settings: %q", g.Name, g.Type, value)
	}

	g.URL = strings.TrimSpace(fields[len(fields)-2])
	g.Members = splitMembers(fields[2 : len(fields)-2])

	interval, timeout, tolerance, err := parseProbeSettings(fields[len(fields)-1])
	if err != nil {
		return Group{}, fmt.Errorf("group %q: %w", g.Name, err)
	}
	g.Interval, g.Timeout, g.Tolerance = interval, timeout, tolerance

	if len(g.Members) == 0 {
		return Group{}, fmt.Errorf("group %q holds no member", g.Name)
	}

	return g, nil
}

// splitMembers keeps the members that are not an empty field, so a stray
// trailing backtick does not become a member that matches nothing.
func splitMembers(fields []string) []string {
	out := make([]string, 0, len(fields))
	for _, f := range fields {
		member := strings.TrimSpace(f)
		if member != "" {
			out = append(out, member)
		}
	}
	return out
}

// parseProbeSettings reads "interval[,timeout][,tolerance]".
func parseProbeSettings(field string) (interval, timeout, tolerance int, err error) {
	parts := strings.Split(field, ",")
	if len(parts) > 3 {
		return 0, 0, 0, fmt.Errorf("probe settings %q hold more than interval, timeout and tolerance", field)
	}

	values := make([]int, 3)
	for i, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			// An empty field means the reader should use its own default.
			continue
		}
		n, convErr := strconv.Atoi(part)
		if convErr != nil {
			return 0, 0, 0, fmt.Errorf("probe setting %q is not a number", part)
		}
		values[i] = n
	}

	if values[0] <= 0 {
		return 0, 0, 0, fmt.Errorf("probe interval must be positive, got %d", values[0])
	}

	return values[0], values[1], values[2], nil
}

// ruleFileName is the last path element of a ruleset URL, which is also how a
// rule file is named on disk.
func ruleFileName(source string) string {
	if i := strings.IndexAny(source, "?#"); i >= 0 {
		source = source[:i]
	}

	return path.Base(source)
}

// ruleLines drops blank lines and comments, so the payload handed to mihomo
// holds rules and nothing else.
func ruleLines(content string) []string {
	out := make([]string, 0, strings.Count(content, "\n")+1)

	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(strings.TrimSuffix(line, "\r"))
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") || strings.HasPrefix(line, "//") {
			continue
		}
		out = append(out, line)
	}

	return out
}

// memberReference reports whether a group member is a reference to another
// group rather than a regular expression, and if so returns the name.
func memberReference(member string) (string, bool) {
	if !strings.HasPrefix(member, inlinePrefix) {
		return "", false
	}

	return strings.TrimPrefix(member, inlinePrefix), true
}

// Render turns the template into the three sections it owns: the proxy groups
// the surviving nodes fill, the rule providers carrying the rule files, and the
// rule list that joins them.
//
// keep holds the names of the nodes that passed screening. The template decides
// where they go, never which of them exist.
func (t *Template) Render(keep map[string]struct{}) (groups, rules []any, providers map[string]any, err error) {
	names := make([]string, 0, len(keep))
	for name := range keep {
		names = append(names, name)
	}
	sort.Strings(names)

	matches, dropped := t.classify(names)

	// Every rule that loses its group falls back to []FINAL, so []FINAL has to
	// be there to receive them. It always is while its group still holds
	// something, which is what this checks before anything is written out.
	if dropped[t.Final] {
		return nil, nil, nil, fmt.Errorf("the []FINAL group %q holds nothing to catch unmatched traffic with", t.Final)
	}

	groups = t.renderGroups(matches, dropped)
	rules, providers = t.renderRules(dropped)

	return groups, rules, providers, nil
}

// classify matches every pattern against the surviving names and works out
// which groups still have something to hold.
//
// matches is keyed by group and then by member, so a group holding several
// patterns keeps the nodes each one claimed where that pattern sits in the
// template. dropped holds the groups that reached nothing.
func (t *Template) classify(names []string) (map[string][][]string, map[string]bool) {
	matches := make(map[string][][]string, len(t.Groups))
	for _, g := range t.Groups {
		perMember := make([][]string, len(g.Members))
		for i, re := range t.patterns[g.Name] {
			if re == nil {
				continue
			}
			for _, name := range names {
				if re.MatchString(name) {
					perMember[i] = append(perMember[i], name)
				}
			}
		}
		matches[g.Name] = perMember
	}

	// Dropping runs to a fixpoint from "nothing holds" rather than from
	// "everything holds", because a group whose only members reference each
	// other reaches nothing in either direction and must still be dropped. A
	// group made only of references cannot hold until the group it points at
	// does, so a pass that assumed unexamined groups were live would keep a
	// cycle alive forever.
	holds := make(map[string]bool, len(t.Groups))
	for {
		changed := false
		for _, g := range t.Groups {
			if holds[g.Name] {
				continue
			}
			if t.holdsSomething(g, matches[g.Name], holds) {
				holds[g.Name] = true
				changed = true
			}
		}
		if !changed {
			break
		}
	}

	dropped := make(map[string]bool)
	for _, g := range t.Groups {
		if !holds[g.Name] {
			dropped[g.Name] = true
		}
	}

	return matches, dropped
}

// holdsSomething reports whether anything reaches a group: a node one of its
// patterns claimed, or a member naming a target that itself holds something.
// A reference to a builtin always holds, since DIRECT and REJECT exist
// whatever the template says.
func (t *Template) holdsSomething(g Group, perMember [][]string, holds map[string]bool) bool {
	for _, names := range perMember {
		if len(names) > 0 {
			return true
		}
	}

	for _, member := range g.Members {
		ref, ok := memberReference(member)
		if !ok {
			continue
		}
		if builtinTargets[ref] || holds[ref] {
			return true
		}
	}

	return false
}

// renderGroups writes the groups that still hold something, in template order.
func (t *Template) renderGroups(matches map[string][][]string, dropped map[string]bool) []any {
	out := make([]any, 0, len(t.Groups))

	for _, g := range t.Groups {
		if dropped[g.Name] {
			continue
		}

		entry := map[string]any{
			"name":    g.Name,
			"type":    g.Type,
			"proxies": t.renderMembers(g, matches[g.Name], dropped),
		}
		// Only the types that probe have anything to probe with. A select group
		// carries a URL it would never use.
		if g.Type != "select" {
			if g.URL != "" {
				entry["url"] = g.URL
			}
			if g.Interval > 0 {
				entry["interval"] = g.Interval
			}
			if g.Timeout > 0 {
				entry["timeout"] = g.Timeout
			}
			if g.Type == "url-test" && g.Tolerance > 0 {
				entry["tolerance"] = g.Tolerance
			}
		}

		out = append(out, entry)
	}

	return out
}

// renderMembers lays a group's members out in template order, so the first
// member the template named stays the one a reader first meets.
//
// A reference to a group that dropped is skipped rather than left in: that is
// what lets a group point at one group which lost every node while still
// pointing at others which did not.
func (t *Template) renderMembers(g Group, perMember [][]string, dropped map[string]bool) []any {
	out := make([]any, 0, len(g.Members))
	seen := make(map[string]bool, len(g.Members))

	add := func(name string) {
		if seen[name] {
			return
		}
		seen[name] = true
		out = append(out, name)
	}

	for i, member := range g.Members {
		ref, isRef := memberReference(member)
		if isRef {
			if !builtinTargets[ref] && dropped[ref] {
				continue
			}
			add(ref)
			continue
		}
		for _, name := range perMember[i] {
			add(name)
		}
	}

	return out
}

// renderRules turns the ruleset list into mihomo rules, and collects the
// inline rule providers the ones reading from a file need.
//
// A rule whose group dropped is sent to []FINAL instead. Publishing a rule
// naming a group that is not in the document would leave it matching nothing,
// which reads as a rule that never fires rather than as a group that emptied.
func (t *Template) renderRules(dropped map[string]bool) ([]any, map[string]any) {
	providers := map[string]any{}
	rules := make([]any, 0, len(t.Rulesets))
	seen := make(map[string]bool, len(t.Rulesets))

	for _, rs := range t.Rulesets {
		target := rs.Group
		if dropped[target] {
			target = t.Final
		}

		var rule string
		if expr, ok := rs.inlineExpression(); ok {
			if strings.EqualFold(expr, "FINAL") {
				rule = "MATCH," + target
			} else {
				rule = expr + "," + target
			}
		} else {
			name := ruleFileName(rs.Source)
			if _, taken := providers[name]; !taken {
				providers[name] = map[string]any{
					"type":     "inline",
					"behavior": "classical",
					"payload":  t.payload[name],
				}
			}
			rule = "RULE-SET," + name + "," + target
		}

		// The same source can be named twice for the same group. Emitting the
		// rule twice would only make the reader match it twice.
		if seen[rule] {
			continue
		}
		seen[rule] = true
		rules = append(rules, rule)
	}

	return rules, providers
}
