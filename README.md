# Mapper

**One controller mapper for everything: a knob of a MIDI controller on any mixer, module or
effect, and on several of them at once.** A SuperCollider extension.

```supercollider
m = Mapper.default;                                    // the one everything shares
m.useKtl(\m1, "akai-midimix");                         // MKtl(\m1), made if it is not there
m.units.put(\eq, ParaEQ.onBus(0));                     // anything that lists its parameters
m.map(\m1, \kn_1_1, [\units, \eq, \gain3], -6, 6);     // a knob on the eq's third band
m.map(\m1, \kn_1_1, [\tidal, \bright]);                // the same knob, Tidal's cF "bright" too
m.gui;                                                 // controllers, targets, learn, every mapping
```

![the mapper's window: a Midimix with eight mappings on an eq and a compressor of the main out and on two Tidal controls, the eq's second band on show with its exp curve](ui-shots/mapper-window.png)

The pictures in this README live in `ui-shots/`, which the repository ignores: make them on
your machine with the window open, or read them on the author's.

It depends on nothing. Everything it works with is looked up by name, and a tool that is not
installed is a button or a target that is not there.

Nothing that is mapped needs the mapper to compile either. A mixer, a module or a chain of
effects registers with it when it is there, and works the same when it is not.

## How it connects to the other tools

| tool | what the mapper does with it |
|------|------------------------------|
| [Modality toolkit](https://github.com/ModalityTeam/Modality-toolkit) | the controllers: `MKtl`, its descriptions, find and connect. Without it there are no controllers, and Tidal mappings still work, moved from code |
| [sc-fx-kit](https://github.com/fracnesco/sc-fx-kit) | every effect built on its `FxUnit` lists its parameters, so it can be put on the mapper by name (`\units`). Its knobs, in every effect window, are learnt by clicking them and wear a dot while they are mapped |
| [sc-EQ](https://github.com/fracnesco/sc-EQ) | `ParaEQ`, an `FxUnit`: its bands, cuts and switches are targets |
| [TidalCycles](https://tidalcycles.org) | `[\tidal, name]` is Tidal's `cF 0 "name"`, sent to its controller port, 6010 |
| [adaptive-textures](https://github.com/fracnesco/adaptive-textures) | registers every engine as `\AdaptiveTextures`: parameters, switches, the selected voice, snapshots, both effect chains (its `engine-controls` branch) |
| [superdirt-gui](https://github.com/fracnesco/superdirt-gui) | registers as `\sdg`: the orbit faders and every insert. This is on a branch of SuperDirtGUI that is not published yet |

It started as SuperDirtGUI's MIDI mapping (`SDGMidi`), taken out so that nothing it maps has to
be SuperDirtGUI.

Written for SuperCollider 3.13 on macOS.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-mapper.git");
thisProcess.recompile;
```

### From a clone

```sh
git clone https://github.com/fracnesco/sc-mapper.git
```

Open `install.scd` in the clone and evaluate its first block, then recompile the class library
(ctrl/cmd + shift + L). It symlinks the folder into your extensions folder, so the clone stays
the single copy. `ln -s` is macOS and Linux; on Windows use the quark.

One or the other, not both: two copies on the class path is a duplicate class error at compile
time.

### Check that it took

```supercollider
Mapper.default.targets.size;     // a number, not an error
Mapper.isAvailable;              // true when Modality is installed
```

## Targets and providers

A target is something a controller can move, named by an address that stays the same across
rebuilds, reloads and sessions: `[provider, ... path]`. The first item says who owns the thing,
the rest is that owner's business.

| address | moves |
|---------|-------|
| `[\units, \eq, \gain3]` | a parameter of an effect held by name (see below) |
| `[\tidal, \bright]` | Tidal's `cF 0 "bright"`, through its port 6010 |
| `[\sdg, \fader, 0]` | SuperDirtGUI's orbit 0 fader, when SuperDirtGUI is running |
| `[\sdg, \insert, 'orbit 0', \growl, 0, \drive1]` | the first Growl insert of orbit 0, its drive |

A **provider** is whoever registers under a key and describes what it has:

```supercollider
Mapper.register(\mine, ~provider);
```

It answers `mapTargetAt(path)`, a description of one thing or nil, and `mapTargets`, the
descriptions of everything it has now. A description is an Event:

```supercollider
(path: [\level], label: "my level", kind: \knob, spec: ControlSpec(0, 1),
 get: { ~level }, set: { |v| ~level = v }, group: "my module")
```

`kind` is `\knob`, `\toggle` (on/off), `\trigger` (it fires) or `\menu` (the index of an item).
The mapper knows no module by name. A target whose provider is not there, or that the provider
no longer has, does not exist: setting it does nothing, and a mapping to it **waits**, and
starts working when it comes back. That is the one thing this extension has to get right:
mappings are between names, so nothing is lost when what they name is rebuilt.

### Effects by name

Anything whose class answers `mapParams` and that answers `mapGet(key)` and `mapSet(key,
value)` - every effect built on sc-fx-kit's `FxUnit` does - can be put on the mapper under a name:

```supercollider
Mapper.units.put(\masterEq, FxChain.master.eq);
Mapper.default.targets.select { |t| t.address[1] == \masterEq }.collect(_.label);
Mapper.units.removeAt(\masterEq);
```

## Mappings

```supercollider
m.map(\m1, \sl_1, [\sdg, \fader, 0]);                          // the whole range
m.map(\m1, \kn_1_1, [\units, \eq, \freq2], 200, 2000, \exp);   // lo, hi, a curve
m.map(\m1, \bt_1_1, [\units, \eq, \lowCutOn]);                 // a button flips a switch
m.learn([\units, \eq, \gain4]);                                // the next element moved
m.unmap([\units, \eq, \gain4]);
```

**An element may move several targets, each with a range of its own. A target has one
element**: mapping a target again replaces the mapping it had.

- `lo` and `hi` are in the target's own units, along its own warp; `lo` above `hi` turns the
  control round.
- `curve` is `\lin`, `\exp`, `\log`, `\scurve`, a number (a bend) or an `Env` drawn by hand.
- `takeover: \pickup` (the default) leaves the target alone until the control reaches the value
  it has, so nothing jumps; `\jump` sets at once.
- A button on an on/off target flips it (`mode: \toggle`) or holds it while down
  (`\momentary`); on a trigger it fires. Its LED follows the target.
- A target that something else moves (it has an `owner`) refuses a mapping.

## The window

![the window with the targets folded away: a Tidal control on show, its curve drawn by hand with three segments](ui-shots/mapper-drawn-curve.png)

`Mapper.default.gui`. On the left, every target there is, as a tree: a provider, its groups, the
targets in each. Click a heading to fold or open it (**+** and **-** do them all), type to
narrow it - what matches is opened. A dot marks the mapped ones and a heading counts them.
Point at a row and the hint bar says what it is: its kind, its range, where it is now, its
address, what is mapped to it. The **targets** button folds the whole panel away, and the
window remembers that. On the right, the controllers (**find**,
**connect**, **forget**), then the mappings by control, so the targets of one knob sit together,
and under them the one on show: its range, takeover, button mode and curve.

**midi learn** switches learn mode on for every window that has something to map: click a fader
or a knob there, move a control, and again until it is off. **learn** maps the next element
moved to the target on show - a control that already moves something keeps that as well.

## Saving

The code is the storage. `Mapper.default` reads
`Platform.userAppSupportDir/sc-mapper/default.scd` the first time it is asked for, and writes it
a moment after a mapping changes.

```supercollider
m.code.postln;                // the lines that make the mappings again
m.save("~/my-maps.scd");
m.load("~/my-maps.scd");      // in place of the ones there are
Mapper.autoSave = false;      // keep the file as it is
```

A provider whose own sessions save its mappings (SuperDirtGUI does, as `midi.scd`) is left out
of that file.

## Files

| file | what it is |
|------|------------|
| [classes/Mapper.sc](classes/Mapper.sc) | the mapper: controllers, providers, mappings, learn, the code |
| [classes/MapperMap.sc](classes/MapperMap.sc) | one element moving one target: range, curve, pickup, button modes |
| [classes/MapperTarget.sc](classes/MapperTarget.sc) | something that can be moved, by address |
| [classes/MapperUnits.sc](classes/MapperUnits.sc) | the provider for effects held by name |
| [classes/MapperWindow.sc](classes/MapperWindow.sc) | the window |
| [classes/MapperHintBar.sc](classes/MapperHintBar.sc) | its hint bar |
| [install.scd](install.scd) | link, verify, unlink |
| [HelpSource/](HelpSource/Classes) | help files |

## Issues

https://github.com/fracnesco/sc-mapper/issues

## License

MIT - see [LICENSE](LICENSE).
