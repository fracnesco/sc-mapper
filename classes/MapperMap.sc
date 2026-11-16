// =====================================================================
// MapperMap - one element of one controller moving one MapperTarget
//
//   Mapper.default.map(\m1, \kn_1_1, [\sdg, \param, 0, \global_sdgecho, \sdgfb], 0, 0.9, \exp);
//
// The element's 0..1 goes through the curve, then from lo to hi along the
// target's own warp: a fader keeps its amp curve, a frequency its octaves,
// and a curve on top bends that. lo above hi turns it round, as invert does.
// An element may have several maps - one knob, many targets, each with a
// range of its own - and a target has one.
//
// curve:
//   \lin            straight
//   \exp, \log      slow then fast, fast then slow (curve 4 and -4)
//   \scurve         slow at both ends
//   a Number        lincurve's curve: 0 straight, positive slow first
//   an Env          by hand: its levels, 0..1, over its whole duration
//
// takeover: \pickup (the default) leaves the target alone until the element
// reaches the value the target has - after the mapping is made, and after
// anything else moved the target (the mouse, a session, code) - so nothing
// jumps. \jump sets at once.
//
// A button (or a note pair, bt_1_1 for bt_1_1_on/_off) on an on/off target
// flips it (mode \toggle) or holds it on while down (\momentary); on a
// trigger it fires; on anything else it flips between lo and hi (\toggle)
// or holds hi while down (\momentary). A knob on an on/off target turns it
// on above the middle.
// =====================================================================

MapperMap {
	classvar <>catchRange = 0.02;
	classvar <curveNames = #[\lin, \exp, \log, \scurve];

	var <mapper, <ktl, <element, <target;
	var loSet, hiSet, <curve, <invert = false, <takeover = \pickup, <mode = \toggle;
	var caught = false, lastUnit, lastSet, <lastInput, held = false;

	*new { |mapper, ktl, element, target, lo, hi, curve, invert, takeover, mode|
		^super.newCopyArgs(mapper, ktl.asSymbol, element.asSymbol, target).init(lo, hi, curve, invert, takeover, mode)
	}

	init { |argLo, argHi, argCurve, argInvert, argTakeover, argMode|
		// nil is the end of the target's own range, whatever that turns out to
		// be: a map made before its target exists learns the range when it does
		loSet = argLo !? (_.asFloat);
		hiSet = argHi !? (_.asFloat);
		curve = this.class.checkCurve(argCurve ? \lin);
		invert = argInvert == true;
		takeover = if (argTakeover == \jump) { \jump } { \pickup };
		mode = if (argMode == \momentary) { \momentary } { \toggle };
	}

	*checkCurve { |c|
		^case
		{ c.isKindOf(Env) } { c }
		{ c.isNumber } { c.asFloat }
		{ curveNames.includes(c.asSymbol) } { c.asSymbol }
		{ Error("MapperMap: a curve is %, a Number or an Env, not %".format(curveNames, c.asCompileString)).throw }
	}

	// every change goes through the mapper, which tells its dependants
	lo { ^loSet ?? { target.range[0].asFloat } }
	hi { ^hiSet ?? { target.range[1].asFloat } }
	lo_ { |x| loSet = x !? (_.asFloat); this.prChanged }
	hi_ { |x| hiSet = x !? (_.asFloat); this.prChanged }
	curve_ { |c| curve = this.class.checkCurve(c); this.prChanged }
	invert_ { |bool| invert = bool == true; this.prChanged }
	takeover_ { |sym| takeover = if (sym == \jump) { \jump } { \pickup }; caught = false; this.prChanged }
	mode_ { |sym| mode = if (sym == \momentary) { \momentary } { \toggle }; this.prChanged }

	prChanged { caught = false; mapper.changed(\map, this) }

	isWaiting { ^target.exists.not }
	isCaught { ^caught }

	// ------------------------------------------------------------------ shaping

	// 0..1 -> 0..1 through the curve
	shape { |x|
		x = x.clip(0, 1);
		if (invert) { x = 1 - x };
		^case
		{ curve == \lin } { x }
		{ curve == \exp } { x.lincurve(0, 1, 0, 1, 4) }
		{ curve == \log } { x.lincurve(0, 1, 0, 1, -4) }
		{ curve == \scurve } { (1 - cos(x * pi)) / 2 }
		{ curve.isNumber } { x.lincurve(0, 1, 0, 1, curve) }
		{ curve.isKindOf(Env) } { curve.at(x * curve.duration).clip(0, 1) }
		{ x }
	}

	// where 0..1 of the element sends the target, in the target's units
	out { |x| ^target.spec.map(this.prUnit(x)) }

	// the same, as 0..1 of the target's spec
	prUnit { |x|
		var spec = target.spec;
		^spec.unmap(this.lo).blend(spec.unmap(this.hi), this.shape(x))
	}

	// --------------------------------------------------------------- moving

	// what the element says, already as a control: (value:, button:, press:)
	handle { |c|
		var x = c[\value];
		if (x.isNumber.not or: { target.exists.not }) { ^this };
		lastInput = x;
		case
		{ c[\button] } { this.prButton(c[\press]) }
		{ target.isTrigger } { if (x > 0.5 and: { held.not }) { target.trigger }; held = x > 0.5 }
		{ target.isToggle } { this.prSet((x > 0.5).binaryValue) }
		{ this.prContinuous(x) };
		mapper.changed(\moved, this);
	}

	prButton { |press|
		case
		{ target.isTrigger } { if (press) { target.trigger } }
		{ target.isToggle } {
			if (mode == \momentary) { this.prSet(press.binaryValue) } {
				if (press) { this.prSet(1 - (target.value ? 0)) }
			}
		}
		{ mode == \momentary } { this.prSet(if (press) { this.hi } { this.lo }) }
		{
			// nearer hi goes to lo, nearer lo to hi
			var lo = this.lo, hi = this.hi;
			if (press) { this.prSet(target.value !? { |v| if ((v absdif: hi) < (v absdif: lo)) { lo } { hi } } ? hi) }
		}
	}

	prContinuous { |x|
		var u = this.prUnit(x), spec = target.spec, current;
		if (takeover == \jump) { ^this.prSet(spec.map(u)) };
		current = target.value !? { |v| spec.unmap(v) };
		// something else moved it since we did: wait for the element to come to it again
		if (caught and: { lastSet.notNil } and: { current.notNil } and: { (current - lastSet).abs > 0.001 }) { caught = false };
		if (caught.not and: { current.notNil }) {
			if ((u - current).abs <= catchRange
				or: { lastUnit.notNil and: { (lastUnit - current).sign != (u - current).sign } }) {
				caught = true
			}
		};
		lastUnit = u;
		if (caught or: { current.isNil }) { this.prSet(spec.map(u)); lastSet = u };
	}

	prSet { |v|
		target.value = v;
		mapper.prFeedback(this);
	}

	// ------------------------------------------------------------------ code

	// the line that makes it again. `receiver` is what the line calls map on,
	// `address` the target as that receiver names it (a facade may drop the
	// provider from the front)
	asCode { |receiver = "Mapper.default", address|
		var code = { |x| Mapper.codeOf(x) };
		var args = [code.(ktl), code.(element), code.(address ? target.address)];
		var range = target.range, lo = this.lo, hi = this.hi;
		// lo and hi only when they were said, and are not the target's whole range
		if ((loSet.notNil or: { hiSet.notNil }) and: { lo != range[0] or: { hi != range[1] } }) {
			args = args ++ [code.(lo), code.(hi)]
		};
		if (curve != \lin) { args = args.add("curve: " ++ if (curve.isKindOf(Env)) { curve.asCompileString } { code.(curve) }) };
		if (invert) { args = args.add("invert: true") };
		if (takeover != \pickup) { args = args.add("takeover: \\jump") };
		if (mode != \toggle) { args = args.add("mode: \\momentary") };
		^"%.map(%);".format(receiver, args.join(", "))
	}

	label { ^"% % -> %".format(ktl, element, target.label) }

	printOn { |stream| stream << this.class.name << "(" << this.label << ")" }
}
