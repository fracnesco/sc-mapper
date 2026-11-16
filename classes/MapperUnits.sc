// =====================================================================
// MapperUnits - a provider for things that list their own parameters
//
// An effect (sc-eq's FxUnit and everything built on it) says what can be
// moved on it: its class answers `mapParams`, a list of
// (key:, label:, kind:, spec:, section:), and it answers `mapGet(key)` and
// `mapSet(key, value)`. This provider holds such things by name, and makes
// each of their parameters a target [\units, name, key]:
//
//   Mapper.units.put(\masterEq, FxChain.master.eq);
//   Mapper.default.map(\m1, \kn_1_1, [\units, \masterEq, \gain3]);
//   Mapper.units.removeAt(\masterEq);
//
// Anything else that holds effects - a mixer with inserts, a module with a
// chain per pad - is a provider of its own and uses `MapperUnits.describe`
// for the descriptions, so they read the same everywhere.
// =====================================================================

MapperUnits {
	var <units;

	*new { ^super.new.init }

	init { units = IdentityDictionary.new }

	// One parameter of one unit as a target description, or nil when the
	// unit has no such parameter. `name` is what the unit is called in labels
	// and `group` where it sits in a browser.
	*describe { |unit, key, name, group, path|
		var e;
		if (unit.isNil) { ^nil };
		key = key.asSymbol;
		e = unit.class.tryPerform(\mapParam, key);
		if (e.isNil) { ^nil };
		^(
			path: path,
			label: "% %".format(name, e[\label] ? key),
			kind: e[\kind] ? \knob,
			spec: e[\spec],
			group: "%/%".format(group ? name, e[\section] ? \main),
			get: { unit.mapGet(key) },
			set: { |v| unit.mapSet(key, v) }
		)
	}

	// every parameter of one unit; pathOf makes each one's path from its key
	*describeAll { |unit, name, group, pathOf|
		var list = unit !? { unit.class.tryPerform(\mapParams) } ? [];
		^list.collect { |e| this.describe(unit, e[\key], name, group, pathOf.value(e[\key])) }
	}

	put { |name, unit|
		units[name.asSymbol] = unit;
		this.changed(\mapTargets);
	}

	removeAt { |name|
		units.removeAt(name.asSymbol);
		this.changed(\mapTargets);
	}

	at { |name| ^units[name.asSymbol] }

	// ---------------------------------------------------------- the provider

	mapLabel { ^"effects" }

	mapTargetAt { |path|
		var name = path[0], key = path[1];
		if (name.isNil or: { key.isNil }) { ^nil };
		^this.class.describe(units[name.asSymbol], key, name, name, [name.asSymbol, key.asSymbol])
	}

	mapTargets {
		var out = [];
		units.keys.asArray.sort.do { |name|
			out = out ++ this.class.describeAll(units[name], name, name, { |key| [name, key] });
		};
		^out
	}

	mapAddressOf { |unit, key|
		var name = units.findKeyForValue(unit);
		^name !? { [name, key.asSymbol] }
	}
}
