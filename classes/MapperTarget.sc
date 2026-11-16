// =====================================================================
// MapperTarget - something a controller can move, named by an address
// that stays the same across rebuilds, reloads and sessions
//
//   t = MapperTarget(Mapper.default, [\sdg, \fader, 0]);
//   t.exists; t.value; t.value = 0.4; t.spec; t.label;
//
// An address is [provider, ... path]: the first item names whoever owns
// the thing (a mixer, a module, a chain of effects - see Mapper.register),
// the rest is the provider's own business. The target asks its provider
// for a description of that path, an Event:
//
//   (label: "orbit 0 fader", kind: \knob, spec: aControlSpec,
//    get: { value }, set: { |v| ... }, group: "mixer/orbit 0")
//
//   kind     \knob (the default), \toggle (on/off), \trigger (it fires) or
//            \menu (the index of an item)
//   spec     the range a mapping's lo and hi live in, and its warp
//   get set  in the target's own units: an amp, a frequency, 0/1
//   trigger  what a \trigger does; without it, set(1)
//   range    where a new mapping's lo and hi start, if not the whole spec
//   owner    something else that moves it (a macro): it cannot be mapped
//
// A provider that is not there, or that answers nil for the path, makes a
// target that does not exist: setting it does nothing, and a mapping to it
// waits. The description is asked for again whenever it is a moment old,
// so a target follows what it names when that is rebuilt.
// =====================================================================

MapperTarget {
	classvar <>keep = 0.25;		// seconds a description is trusted for

	var <mapper, <address;
	var desc, descTime = -1;

	*new { |mapper, address|
		address = address.asArray.collect { |x| if (x.isString) { x.asSymbol } { x } };
		if (address.isEmpty) { Error("MapperTarget: an address is [provider, ...]").throw };
		^super.newCopyArgs(mapper, address)
	}

	== { |that| ^that.isKindOf(MapperTarget) and: { that.address == address } }
	hash { ^address.hash }

	providerKey { ^address[0] }
	path { ^address.drop(1) }
	provider { ^mapper.providers[address[0]] }

	// ---------------------------------------------------------------- what it is

	// the provider's description, asked again when it is not fresh
	desc {
		var now = Main.elapsedTime;
		if ((now - descTime) > keep) { this.resolve };
		^desc
	}

	// ask now: nil when the provider is gone, has no such path, or throws
	resolve {
		var p = this.provider;
		desc = p !? { try { p.mapTargetAt(this.path) } };
		descTime = Main.elapsedTime;
		^desc
	}

	exists { ^this.resolve.notNil }

	kind { ^this.desc !? { |d| d[\kind] } ? \knob }
	isToggle { ^this.kind == \toggle }
	isTrigger { ^this.kind == \trigger }
	isMenu { ^this.kind == \menu }

	// what else moves it, if anything does: then a controller may not
	owner { ^this.desc !? { |d| d[\owner] } }

	// --------------------------------------------------------------- its value

	spec {
		var d = this.desc;
		^case
		{ this.isToggle or: { this.isTrigger } } { ControlSpec(0, 1, \lin, 1, 0) }
		{ d.notNil and: { d[\spec].notNil } } { d[\spec].asSpec }
		{ ControlSpec(0, 1) }
	}

	// where a new mapping's lo and hi start
	range {
		var d = this.desc, sp;
		^(d !? { d[\range] }) ?? { sp = this.spec; [sp.minval, sp.maxval] }
	}

	// in the target's own units; nil when it does not exist
	value {
		var d = this.desc;
		^d !? { if (d[\kind] == \trigger) { 0 } { try { d[\get].value } } }
	}

	value_ { |x|
		var d = this.desc;
		if (d.isNil or: { x.isNumber.not }) { ^this };
		if (d[\kind] == \trigger) {
			if (x > 0.5) { this.trigger };
		} {
			d[\set].value(x);
		};
	}

	trigger {
		var d = this.desc;
		if (d.isNil) { ^this };
		if (d[\trigger].notNil) { d[\trigger].value } { d[\set].value(1) };
	}

	// ------------------------------------------------------------------ naming

	label { ^(this.desc !? { |d| d[\label] }) ?? { address.collect(_.asString).join(" ") } }

	// where it sits in a browser: "mixer/orbit 0"
	group { ^(this.desc !? { |d| d[\group] }) ? address[0].asString }

	asCode { ^Mapper.codeOf(address) }

	printOn { |stream| stream << this.class.name << "(" << address.asCompileString << ")" }
}
