// =====================================================================
// MapperHintBar - a strip that says what the control under the pointer does
//
// The family's hint bar (ModuleHintBar, FxHintBar, SDGHintBar), kept here as
// a copy of its own so the mapper needs none of them.
//
//   hints = MapperHintBar("what the window is for");
//   hints.attach(button, "what the button does");
//   hints.attach([knob, box], { "computed when the pointer arrives" });
//   hints.watch(window);          // moving onto nothing puts the default back
//   hints.note("something just happened");   // from any thread
//   layout: ..., hints.view
// =====================================================================

MapperHintBar {
	var <view, <text, <>defaultString;

	*new { |defaultString, height = 22| ^super.new.init(defaultString, height) }

	init { |str, height|
		defaultString = str ? "hover over a control and this bar says what it does";
		text = StaticText().font_(Font(Font.defaultSansFace, 9)).stringColor_(MapperWindow.colDim)
			.string_(defaultString);
		view = View().background_(MapperWindow.colWaveBg).fixedHeight_(height);
		view.layout = HLayout(text).margins_([8, 1, 8, 1]);
	}

	watch { |window|
		var v = window.tryPerform(\view) ? window;
		v !? {
			v.acceptsMouseOver = true;
			v.mouseOverAction = { this.string_(nil) };
		};
	}

	attach { |views, hint|
		views.asArray.do { |v|
			v !? {
				v.acceptsMouseOver = true;
				v.mouseOverAction = { |view, x, y| this.string_(hint.value(view, x, y)) };
			};
		};
		^views
	}

	// said now, whatever the pointer is over; from any thread
	note { |str| { this.string_(str) }.defer }

	string_ { |str| if (text.notNil and: { text.isClosed.not }) { text.string = str ? defaultString } }
}
