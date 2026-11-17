// =====================================================================
// Mapper - controllers on anything, through the Modality toolkit
//
//   m = Mapper.default;                             // the one everything shares
//   m.useKtl(\m1, "akai-midimix");                  // MKtl(\m1), made if it is not there
//   m.map(\m1, \sl_1, [\sdg, \fader, 0]);           // the first fader is orbit 0's
//   m.map(\m1, \kn_1_1, [\sdg, \param, 0, \global_sdgecho, \sdgfb], 0, 0.9, \exp);
//   m.map(\m1, \kn_1_1, [\sdg, \send, 0, \A], 0, 0.5);   // the same knob, a second target
//   m.map(\m1, \kn_2_1, [\tidal, \cut]);            // Tidal's cF 0 "cut"
//   m.learn([\sdg, \solo, 0]);                      // the next element anyone moves solos orbit 0
//   m.targets;                                      // everything that can be mapped, now
//   m.code.postln;                                  // all of it, as the lines above
//   m.save; m.load;                                 // a file of those lines
//
// What a mapping does is MapperMap's; what it can move, MapperTarget's. An
// element may move several targets, each with a range of its own; a target
// has one element, so mapping a target again replaces the mapping it had.
// A mapping whose target is gone waits for it, and one whose MKtl is not
// there yet starts working when it is (polled once a second).
//
// Who can be mapped: providers. Anything registers under a key,
//
//   Mapper.register(\sdg, provider);
//
// and answers two messages - `mapTargetAt(path)`, the description of one
// thing (see MapperTarget) or nil, and `mapTargets`, the descriptions of
// everything it has now, each with its `path`. The mapper knows no module,
// effect or mixer by name, and none of them needs the mapper to compile:
// they register when `\Mapper.asClass` is there. A provider may also answer
//
//   mapAddressOf(unit, key)   the address of a parameter of an effect it
//                             holds, so a knob in that effect's window can
//                             be learnt (sc-eq's FxMapHooks)
//   mapsSavedElsewhere        true: its mappings are saved by its own
//                             sessions, not in the mapper's file
//   mapLabel                  what a browser calls it
//
// and says changed(\mapTargets) when what it has changes. [\tidal, name] is
// the mapper's own: Tidal's cF "name", through its port 6010.
//
// Elements are listened to as dependants (MKtlElement announces every move
// with changed(\doAction)), never through their action, so your own
// element actions keep working beside it. Buttons on on/off targets get
// their LED back when the target changes. Modality is looked up by name:
// without it there are no controllers and nothing else changes.
//
// The code is the storage. `Mapper.default` reads its file the first time
// it is asked for and writes it a moment after a mapping changes
// (Mapper.autoSave). Notifies \maps (the list), \map (one changed), \moved,
// \learn, \ktls, \devices, \providers, \learnMode.
// =====================================================================

Mapper {
	classvar <continuousTypes, <buttonTypes;
	classvar <>tidalPort = 6010;
	classvar default;
	classvar <>defaultPath;			// the file Mapper.default reads and writes
	classvar <>autoLoad = true, <>autoSave = true;

	var <maps, <ktls, <providers;
	var listeners, <armed, learnAction, poller;
	var <tidalValues, tidalAddr;
	var <learnMode = false;
	var window;					// the MapperWindow while it is open
	var <>path;					// where it saves by itself, if anywhere
	var savePending = false, loading = false, hookItems, paintPending = false;

	*initClass {
		continuousTypes = #[\knob, \slider, \encoder, \xfader, \bender, \ribbon, \joyAxis, \mouseAxis];
		buttonTypes = #[\button, \pad, \encoderButton, \switch, \key];
		defaultPath = Platform.userAppSupportDir +/+ "sc-mapper" +/+ "default.scd";
	}

	*modality { ^\MKtl.asClass }
	*isAvailable { ^this.modality.notNil }

	*new { ^super.new.init }

	// The one every module registers with. Made, and its file read, the
	// first time anyone asks.
	*default {
		^default ?? {
			default = this.new;
			default.path = defaultPath;
			default.prInstallHooks;
			if (autoLoad and: { File.exists(defaultPath) }) { default.load(defaultPath) };
			default
		}
	}

	*hasDefault { ^default.notNil }

	// effects held by name on the shared mapper - see MapperUnits
	*units { ^this.default.units }

	// a provider on the shared mapper - what a module calls when it is made
	*register { |key, provider| ^this.default.register(key, provider) }
	*unregister { |key| default !? { default.unregister(key) } }

	init {
		maps = List.new;
		ktls = IdentityDictionary.new;
		providers = IdentityDictionary.new;
		listeners = IdentityDictionary.new;
		tidalValues = IdentityDictionary.new;
		providers[\tidal] = MapperTidal(this);
		this.register(\units, MapperUnits.new);
		this.prStartPoll;
	}

	// --------------------------------------------------------------- providers

	register { |key, provider|
		key = key.asSymbol;
		providers[key] !? { |old| if (old !== provider) { old.removeDependant(this) } };
		providers[key] = provider;
		provider.addDependant(this);
		this.changed(\providers);
		// mappings that waited for it may be there now: their LEDs, the dots
		maps.do { |m| if (m.target.providerKey == key) { this.prFeedback(m) } };
		this.prPaint;
	}

	units { ^providers[\units] }

	unregister { |key|
		key = key.asSymbol;
		providers.removeAt(key) !? { |old| old.removeDependant(this) };
		this.changed(\providers);
		this.prPaint;
	}

	// a provider says what it has changed, or moved some other way
	update { |who, what|
		if (providers.includes(who)) {
			if (what == \mapTargets) { this.changed(\providers) };
			maps.do { |m| if (m.target.isToggle) { this.prFeedback(m) } };
		}
	}

	// Everything that can be mapped now, as MapperTargets, provider by
	// provider. A provider that throws is left out, with a warning.
	targets {
		var out = List.new;
		providers.keys.asArray.sort.do { |key|
			var list = try { providers[key].mapTargets } { |err|
				"Mapper: % could not list its targets: %".format(key, err.errorString).warn; nil
			};
			list.do { |d| out.add(MapperTarget(this, [key] ++ d[\path].asArray)) };
		};
		^out.asArray
	}

	// The address of a parameter of an effect some provider holds, or nil:
	// how a knob in an effect's own window finds what it is called here
	addressOf { |unit, key|
		if (unit.isNil or: { key.isNil }) { ^nil };
		providers.keysValuesDo { |pk, p|
			var path = if (p.respondsTo(\mapAddressOf)) { try { p.mapAddressOf(unit, key) } };
			path !? { ^[pk] ++ path.asArray };
		};
		^nil
	}

	// ------------------------------------------------------------ controllers

	// name is an MKtl's, desc what makes it: MKtl(name, desc). Made here when there
	// is none of that name; one already there is left as it is - with a warning when
	// its description is not this one
	useKtl { |name, desc|
		var cl = this.class.modality, mktl;
		name = name.asSymbol;
		ktls[name] = desc !? (_.asString);
		if (cl.isNil) { ^nil };
		mktl = cl.all[name];
		if (mktl.isNil) {
			if (desc.isNil) { ^nil };
			mktl = try { cl.new(name, desc.asString) } { |err|
				"Mapper: could not make MKtl(%, %): %".format(name.asCompileString, desc.asCompileString, err.errorString).warn;
				nil
			};
		} {
			if (desc.notNil and: { this.class.descMatches(mktl, desc).not }) {
				"Mapper: MKtl(%) is a %, not a % - the mappings on it may land on the wrong elements"
				.format(name.asCompileString, this.class.descName(mktl), desc).warn
			}
		};
		mktl !? { this.prUse(mktl) };
		this.changed(\ktls);
		^mktl
	}

	*descName { |mktl| ^mktl.desc !? { |d| d.name.asString } ? "controller with no description" }

	// "akai-midimix" against the MKtl's own description's name; a * in it is any text
	*descMatches { |mktl, desc|
		var name = this.descName(mktl), parts = desc.asString.split($*).reject(_.isEmpty);
		^parts.isEmpty or: { parts.every { |p| name.containsi(p) } }
	}

	// the MKtls this sclang has, by name
	availableKtls { ^this.class.modality !? { |cl| cl.all.keys.asArray.sort } ? [] }

	ktl { |name| ^this.class.modality !? { |cl| cl.all[name.asSymbol] } }

	// whether the MKtl is there, and has a device under it (else it is virtual)
	ktlState { |name|
		var k = this.ktl(name);
		^case { k.isNil } { \missing } { k.hasDevice } { \connected } { \virtual }
	}

	// what the mapper takes mappings from: forget's the opposite of useKtl.
	// Its mappings go too; the MKtl itself stays, for whoever else uses it
	forgetKtl { |name|
		name = name.asSymbol;
		ktls.removeAt(name);
		this.prRelease(name);
		this.prRemove { |m| m.ktl == name };
		this.changed(\ktls);
	}

	// ---------------------------------------------------------------- devices

	// Modality's MKtl.find for these protocols - every port opened, the lookup made
	// again, the usual list in the post window - then answers the devices
	findDevices { |protocols = #[\midi, \hid], post = true|
		var cl = \MKtlDevice.asClass;
		if (cl.isNil) { "Mapper: finding controllers needs the Modality toolkit".warn; ^[] };
		try { cl.find(protocols, post) } { |err| "Mapper: MKtl.find: %".format(err.errorString).warn };
		this.changed(\devices);
		^this.devices(protocols)
	}

	// The devices the last find saw, one Event each: lookupName, protocol, deviceName,
	// filenames (Modality's descriptions for it: none, one, sometimes several),
	// multiIndex, and ktl - the name of an MKtl already on it, if any
	devices { |protocols = #[\midi, \hid]|
		var lookup = \MKtlLookup.asClass, out = List.new;
		if (lookup.isNil) { ^[] };
		lookup.allFor(protocols).keysValuesDo { |key, info|
			var name = info[\deviceName] ?? {
				info[\deviceInfo] !? { |d| try { d.productName } } ? info[\idInfo] ? key
			}, port;
			// a MIDI device of several ports (the IAC Driver's busses): the port says which
			if (info[\protocol] == \midi) {
				port = try { info[\deviceInfo].bubble.flatten.first.name };
				if (port.notNil and: { port.asString != name.asString }) { name = "% %".format(name, port) };
			};
			out.add((
				lookupName: key, protocol: info[\protocol], deviceName: name.asString,
				filenames: info[\filenames].asArray.collect(_.asString),
				multiIndex: info[\multiIndex], ktl: this.ktlOnDevice(key)
			))
		};
		^out.asArray.sort { |a, b|
			[a[\protocol], a[\deviceName]].join(" ") <= [b[\protocol], b[\deviceName]].join(" ")
		}
	}

	// the name of the MKtl whose device is lookupName's, or nil
	ktlOnDevice { |lookupName|
		var cl = this.class.modality;
		cl ?? { ^nil };
		cl.all.keysValuesDo { |name, k|
			if (k.lookupName == lookupName
				or: { k.device !? { |d| d.name == lookupName } ? false }) { ^name }
		};
		^nil
	}

	// A name for an MKtl of this description, short and free: "akai-midimix" -> \midimix,
	// \midimix2 when that is taken by another device
	suggestName { |device, desc|
		var cl = this.class.modality, base, name, k = 1;
		if (device.notNil) { device[\ktl] !? { |n| ^n } };
		base = (desc ?? { device !? { |d| d[\deviceName] } } ? "ctl").asString;
		base = base.split($-).reject(_.isEmpty).last ? base;
		base = base.select { |c| c.isAlphaNum or: { c == $_ } }.toLower;
		if (base.isEmpty or: { base.first.isAlpha.not }) { base = "ctl" ++ base };
		base = base.keep(16);
		name = base.asSymbol;
		while { ktls.includesKey(name) or: { cl !? { |c| c.all[name].notNil } ? false } } {
			k = k + 1; name = (base ++ k).asSymbol
		};
		^name
	}

	// A found device on Mapper: its MKtl when there is one, made from the
	// description otherwise. Answers the MKtl, or nil (no description, or Modality
	// would not make it). Saved as a useKtl line
	connect { |device, name, desc|
		var mktl;
		if (this.class.isAvailable.not) { "Mapper: controllers need the Modality toolkit".warn; ^nil };
		desc = desc ?? { device[\filenames].first };
		if (desc.isNil) {
			"Mapper: % has no Modality description, so it has no controls to map".format(device[\deviceName]).warn;
			^nil
		};
		name = (name ?? { this.suggestName(device, desc) }).asSymbol;
		mktl = device[\ktl] !? { |n| this.ktl(n) };
		if (mktl.notNil and: { mktl.name != name }) {
			// the device already has an MKtl: use that one, under its own name
			name = mktl.name;
		};
		^this.useKtl(name, desc)
	}

	// ---------------------------------------------------------------- mapping

	// Answers the MapperMap. lo and hi are in the target's own units (nil: its
	// whole range). The element keeps whatever else it moves; the target lets
	// go of the element it had.
	map { |ktl, element, address, lo, hi, curve, invert, takeover, mode|
		var target = MapperTarget(this, address), m;
		ktl = ktl.asSymbol;
		element = element.asSymbol;
		// something else moves it (a macro): map that instead
		target.owner !? { |o|
			"Mapper: % is moved by %, map that instead".format(target.label, o).warn;
			^nil
		};
		maps.copy.do { |x| if (x.target == target) { maps.remove(x) } };
		m = MapperMap(this, ktl, element, target, lo, hi, curve, invert, takeover, mode);
		maps.add(m);
		this.ktl(ktl) !? { |k|
			this.prUse(k);
			// so the code can make it again
			if (ktls[ktl].isNil) { k.desc !? { |d| ktls[ktl] = d.name.asString } };
		};
		this.prFeedback(m);
		this.prMapsChanged;
		^m
	}

	unmap { |address|
		var target = MapperTarget(this, address);
		this.prRemove { |m| m.target == target }
	}

	unmapElement { |ktl, element|
		this.prRemove { |m| m.ktl == ktl.asSymbol and: { m.element == element.asSymbol } }
	}

	remove { |map| this.prRemove { |m| m === map } }

	// every mapping and every controller name: what a file of code starts with
	clear {
		maps = List.new;
		ktls = IdentityDictionary.new;
		this.cancelLearn;
		this.prMapsChanged;
		this.changed(\ktls);
	}

	// the mappings of one provider only - what a provider's own file starts with
	clearProvider { |key|
		key = key.asSymbol;
		this.prRemove { |m| m.target.providerKey == key }
	}

	prRemove { |test|
		var n = maps.size;
		maps = maps.reject(test).asList;
		if (maps.size != n) { this.prMapsChanged };
	}

	prMapsChanged {
		this.changed(\maps);
		this.prPaint;
		this.prSaveSoon;
	}

	mapFor { |address|
		var target = MapperTarget(this, address);
		^maps.detect { |m| m.target == target }
	}

	mapsOf { |ktl, element| ^maps.select { |m| m.ktl == ktl.asSymbol and: { m.element == element.asSymbol } } }

	mapsOfProvider { |key| ^maps.select { |m| m.target.providerKey == key.asSymbol } }

	// whether an element is a button (a note pair counts as one): nil when the
	// controller is not there to ask
	isButton { |ktl, element|
		var k = this.ktl(ktl), d, el;
		if (k.isNil or: { k.elementsDict.isNil }) { ^nil };
		d = k.elementsDict;
		if (d[(element ++ "_on").asSymbol].notNil) { ^true };
		el = d[element.asSymbol];
		^el !? { buttonTypes.includes(el.type) }
	}

	// ---------------------------------------------------------------- the window

	// the mapping editor, MapperWindow; answers it, so .select(address) can follow
	gui {
		var cl = \MapperWindow.asClass;
		if (window.notNil and: { window.isOpen }) { window.front; ^window };
		if (cl.isNil) { "Mapper: no window in this version".warn; ^nil };
		window = cl.new(this);
		^window
	}

	window { ^if (window.notNil and: { window.isOpen }) { window } }

	// ---------------------------------------------------------------- learning

	// the next element moved on any MKtl is mapped to address: a knob as soon as
	// it moves, a button when pressed. action gets the MapperMap
	learn { |address, action|
		if (this.class.isAvailable.not) { "Mapper: learn needs the Modality toolkit".warn; ^this };
		armed = MapperTarget(this, address);
		learnAction = action;
		this.prUseAll;
		this.changed(\learn);
		this.prPaint;
	}

	cancelLearn {
		if (armed.notNil) { armed = nil; learnAction = nil; this.changed(\learn); this.prPaint };
	}

	isLearning { ^armed.notNil }

	// Learn mode: while it is on, a click on anything that can be mapped arms
	// it, one after another. Windows follow \learnMode; the knobs of effect
	// windows are armed through FxMapHooks.
	learnMode_ { |bool|
		bool = (bool == true) and: { this.class.isAvailable };
		if (bool == learnMode) { ^this };
		learnMode = bool;
		if (bool.not) { this.cancelLearn };
		this.changed(\learnMode, bool);
		this.prPaint;
	}

	// ------------------------------------------------------------------- Tidal

	// Tidal's controller input: OSC /ctrl name value to its port, read in a
	// pattern with cF 0 "name" (cI, cS ...). Tidal must listen: cCtrlListen,
	// on by default
	sendTidal { |name, value|
		name = name.asSymbol;
		tidalValues[name] = value;
		tidalAddr = tidalAddr ?? { NetAddr("127.0.0.1", tidalPort) };
		tidalAddr.sendMsg('/ctrl', name.asString, value.asFloat);
	}

	tidalPort_ { |port| tidalPort = port; tidalAddr = nil }

	// -------------------------------------------------------------------- code

	// The mappings as code that makes them again. Those of a provider whose
	// own sessions save them (mapsSavedElsewhere) are left out, unless asked
	// for by `all`.
	code { |all = false|
		var lines = List[
			"// Mapper mappings - run it (the whole file) to put them back",
			"// Mapper.default.map(ktl, element, target, lo, hi, curve:, invert:, takeover:, mode:) - see Mapper's help",
			"Mapper.default.clear;"
		];
		var kept = maps.select { |m| all or: { this.prSavedHere(m) } };
		ktls.keys.asArray.sort.do { |k|
			lines.add("Mapper.default.useKtl(%, %);".format(this.class.codeOf(k), ktls[k].asCompileString))
		};
		kept.do { |m| lines.add(m.asCode) };
		^lines.join("\n") ++ "\n"
	}

	prSavedHere { |m|
		var p = providers[m.target.providerKey];
		^(p.notNil and: { p.respondsTo(\mapsSavedElsewhere) } and: { p.mapsSavedElsewhere == true }).not
	}

	// x as you would type it: \\kn_1_1, [\\fader, 0], 0.25
	*codeOf { |x|
		^case
		{ x.isKindOf(Symbol) and: { "^[A-Za-z_][a-zA-Z0-9_]*$".matchRegexp(x.asString) } } { "\\" ++ x }
		{ x.isKindOf(Symbol) } { x.asCompileString }
		{ x.isFloat } { x.round(0.0001).asString }
		{ x.isArray and: { x.isString.not } } { "[" ++ x.collect { |y| this.codeOf(y) }.join(", ") ++ "]" }
		{ x.asCompileString }
	}

	save { |argPath|
		var p = (argPath ? path), f;
		if (p.isNil) { "Mapper: save needs a path".warn; ^false };
		p = p.standardizePath;
		File.mkdir(p.dirname);
		f = File(p, "w");
		if (f.isOpen.not) { "Mapper: could not write %".format(p).warn; ^false };
		f.write(this.code);
		f.close;
		^true
	}

	// a file of mappings, in place of the ones there are: an empty file, or one
	// that is not there, clears them. Answers false when the file does not run
	load { |argPath|
		var p = (argPath ? path);
		if (p.isNil) { "Mapper: load needs a path".warn; ^false };
		p = p.standardizePath;
		if (File.exists(p).not) { this.clear; ^true };
		^this.loadCode(File.readAllString(p), p)
	}

	// `env` is what the code may speak of besides Mapper.default: a facade's
	// ~sdg, say. The file's Mapper.default is this one only when this is it.
	loadCode { |text, where = "the mappings", env|
		var func, ok = true;
		if (text.isNil or: { text.every(_.isSpace) }) { this.clear; ^true };
		func = thisProcess.interpreter.compile(text);
		if (func.isNil) { "Mapper: % does not compile".format(where).warn; ^false };
		loading = true;
		Environment.make { env !? { |e| e.keysValuesDo { |k, v| currentEnvironment[k] = v } } }.use {
			try { func.value } { |err| "Mapper: %: %".format(where, err.errorString).warn; ok = false }
		};
		loading = false;
		^ok
	}

	// the default writes itself a moment after the last change
	prSaveSoon {
		if (loading or: { path.isNil } or: { autoSave.not } or: { this !== default }) { ^this };
		if (savePending) { ^this };
		savePending = true;
		AppClock.sched(1, { savePending = false; this.save; nil });
	}

	// ---------------------------------------------------------------- listening

	prUse { |mktl|
		var func, name = mktl.name;
		if ((listeners[name] !? { |l| l[0] === mktl }) == true) { ^this };
		this.prRelease(name);
		if (mktl.elementsDict.isNil) { ^this };
		func = { |el, what| if (what == \doAction) { this.prMoved(name, el) } };
		mktl.elementsDict.do { |el| el.addDependant(func) };
		listeners[name] = [mktl, func];
		// the LEDs as the targets are now
		maps.do { |m| if (m.ktl == name) { this.prFeedback(m) } };
	}

	prRelease { |name|
		listeners.removeAt(name) !? { |l|
			l[0].elementsDict !? { |d| d.do { |el| el.removeDependant(l[1]) } }
		}
	}

	prUseAll { this.class.modality !? { |cl| cl.all.do { |k| this.prUse(k) } } }

	// an MKtl a mapping names, made since or made again; while learning, any
	prStartPoll {
		if (this.class.isAvailable.not) { ^this };
		poller = SkipJack({ this.prPoll }, 1, name: "Mapper " ++ this.identityHash);
	}

	prPoll {
		var cl = this.class.modality;
		if (armed.notNil) { ^this.prUseAll };
		(maps.collect(_.ktl).asSet ++ ktls.keys).do { |n|
			cl.all[n] !? { |k| this.prUse(k) }
		};
		// an MKtl freed since: its elements are gone
		listeners.keys.copy.do { |n|
			if (cl.all[n] !== listeners[n][0]) { this.prRelease(n) }
		};
	}

	// in Modality's thread - MIDI's, or the one that called valueAction: nothing may throw back into it
	prMoved { |ktl, el|
		try { this.prHandle(ktl, el) } { |err| ("Mapper: " ++ err.errorString).warn }
	}

	prHandle { |ktl, el|
		var c = this.class.control(el), m;
		if (c[\value].isNumber.not) { ^this };
		if (armed.notNil) {
			if (c[\button].not or: { c[\press] }) {
				m = this.map(ktl, c[\key], armed.address);
				armed = nil;
				learnAction.value(m);
				learnAction = nil;
				this.changed(\learn, m);
				this.prPaint;
			};
			^this
		};
		// one element, as many targets as it has
		maps.do { |m| if (m.ktl == ktl and: { m.element == c[\key] }) { m.handle(c) } };
	}

	// An element as a control. A note pair - a pad sending note on when pressed
	// and note off when let go, Modality's notePair - is one button, named as
	// its group: bt_1_1 for bt_1_1_on and bt_1_1_off.
	*control { |el|
		var name = el.name.asString;
		var role = el.elemDesc !? { |d| d[\key] };
		var value = el.value;
		^case
		{ role == \on and: { name.endsWith("_on") } } {
			(key: name.drop(-3).asSymbol, button: true, press: value.isNumber and: { value > 0 }, value: value)
		}
		{ role == \off and: { name.endsWith("_off") } } {
			(key: name.drop(-4).asSymbol, button: true, press: false, value: 0)
		}
		{ buttonTypes.includes(el.type) } {
			(key: el.name, button: true, press: value.isNumber and: { value > 0.5 }, value: value)
		}
		{ (key: el.name, button: false, value: value) }
	}

	// ----------------------------------------------------------------- feedback

	// a button on an on/off target shows it: its LED, or the _on half of a note pair.
	// value_ sends to the device and is no \doAction, so nothing comes back in
	prFeedback { |m|
		var k = this.ktl(m.ktl), el, v;
		if (k.isNil or: { k.elementsDict.isNil } or: { m.target.isToggle.not }) { ^this };
		el = k.elementsDict[(m.element ++ "_on").asSymbol] ?? { k.elementsDict[m.element] };
		v = m.target.value;
		if (el.notNil and: { v.isNumber } and: { buttonTypes.includes(el.type) }) {
			try { el.value = v.clip(0, 1) }
		};
	}

	// the toggles again: something moved them some other way
	refreshFeedback { maps.do { |m| if (m.target.isToggle) { this.prFeedback(m) } } }

	// ------------------------------------------------- effect windows' widgets

	// sc-eq's FxMapHooks, when it is installed: every knob of an effect window
	// that some provider can name gets "learn midi" in its menu, is armed by a
	// click in learn mode, and wears a dot while it is mapped. Only the default
	// mapper does this - there is one set of widgets.
	prInstallHooks {
		var hooks = \FxMapHooks.asClass;
		if (hooks.isNil) { ^this };
		hookItems = hooks.addProvider({ |widget| this.prWidgetItems(widget) });
		hooks.clickHook = { |widget| this.prWidgetClick(widget) };
	}

	prWidgetAddress { |widget| ^this.addressOf(widget.mapUnit, widget.mapKey) }

	prWidgetClick { |widget|
		var address;
		if (learnMode.not) { ^false };
		address = this.prWidgetAddress(widget);
		if (address.isNil) { ^false };
		this.learn(address);
		^true
	}

	prWidgetItems { |widget|
		var address = this.prWidgetAddress(widget), target, m, waiting;
		if (address.isNil or: { this.class.isAvailable.not }) { ^[] };
		target = MapperTarget(this, address);
		m = this.mapFor(address);
		waiting = armed == target;
		^[
			MenuAction(m !? { "midi: % %".format(m.ktl, m.element) } ? "no midi mapping").enabled_(false),
			if (waiting) {
				MenuAction("stop waiting for the controller", { this.cancelLearn })
			} {
				MenuAction("learn midi", { this.learn(address) })
			},
			MenuAction("edit mapping...", { this.gui !? { |w| w.select(address) } }).enabled_(m.notNil),
			MenuAction("remove midi mapping", { this.unmap(address) }).enabled_(m.notNil)
		]
	}

	// the dots, a moment after the last change, on the GUI's clock
	prPaint {
		var hooks = \FxMapHooks.asClass;
		if (hooks.isNil or: { this !== default } or: { paintPending }) { ^this };
		paintPending = true;
		AppClock.sched(0.05, {
			paintPending = false;
			hooks.widgets.do { |w|
				var address = this.prWidgetAddress(w);
				w.hasMap = address.notNil and: { this.mapFor(address).notNil };
			};
			nil
		});
	}

	// ---------------------------------------------------------------------------

	free {
		// the window is Qt: closed on AppClock, so free works from a routine too
		window !? { |w| { w.close }.defer }; window = nil;
		poller !? { poller.stop; poller = nil };
		listeners.keys.copy.do { |n| this.prRelease(n) };
		providers.do { |p| p.removeDependant(this) };
		hookItems !? { |f| \FxMapHooks.asClass !? { |h| h.removeProvider(f); h.clickHook = nil } };
		maps = List.new;
		armed = nil;
		if (default === this) { default = nil };
	}
}


// Tidal's controller input as a provider: [\tidal, name] is cF "name". A
// value has no range of its own - a mapping's lo and hi are the range, and
// nothing clips them.
MapperTidal {
	var <mapper;

	*new { |mapper| ^super.newCopyArgs(mapper) }

	mapLabel { ^"tidal" }

	mapTargetAt { |path|
		var name = path[0];
		if (name.isNil) { ^nil };
		name = name.asSymbol;
		^(path: [name], label: "tidal cF \"%\"".format(name), kind: \knob, group: "tidal",
			spec: ControlSpec(-1e6, 1e6, \lin, 0, 0), range: [0, 1],
			get: { mapper.tidalValues[name] }, set: { |v| mapper.sendTidal(name, v) })
	}

	// the names that have been sent so far
	mapTargets { ^mapper.tidalValues.keys.asArray.sort.collect { |n| this.mapTargetAt([n]) } }
}
