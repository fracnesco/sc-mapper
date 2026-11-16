// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// This is the route for a clone you want to keep editing. If you would
// rather have it installed and updated for you, do not use this file -
// install it as a quark instead, from a fresh sclang:
//
//   Quarks.install("https://github.com/fracnesco/sc-mapper.git");
//   thisProcess.recompile;
//
// One or the other, not both: two copies on the class path is a
// duplicate class error at compile time.
//
// ln -s is macOS and Linux. On Windows, use the quark, or copy this
// folder into Platform.userExtensionDir by hand - .openOS opens it.
//
// Nothing else has to be installed. Controllers come through the Modality
// toolkit (Quarks.install("Modality-toolkit")), looked up by name: without
// it there are no controllers, and nothing else breaks.

(
var src = PathName(thisProcess.nowExecutingPath).pathOnly;
var dst = Platform.userExtensionDir +/+ "Mapper";

// drop the trailing slash, ln -s does not like it
if (src.endsWith("/")) { src = src.drop(-1) };

case
{ File.exists(dst) } {
	("Mapper: % already exists - remove it first if you want to relink."
		.format(dst)).warn;
}
{ true } {
	File.mkdir(Platform.userExtensionDir);
	("ln -s" + src.shellQuote + dst.shellQuote).unixCmd({ |exit|
		if (exit == 0) {
			"Mapper: linked".postln;
			("   " ++ src).postln;
			("-> " ++ dst).postln;
			"".postln;
			"Now recompile the class library: ctrl/cmd + shift + L".postln;
		} {
			("Mapper: linking failed with exit code" + exit).warn;
		};
	});
};
)


// ---------------------------------------------------------------- verify
(
var missing = [\Mapper, \MapperMap, \MapperTarget]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("Mapper is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors.".format(missing.join(", "))).warn;
} {
	"Mapper is installed.".postln;
	"  help: Mapper, then cmd+d".postln;
};
)


// ------------------------------------------------------------- uninstall
(
var link = Platform.userExtensionDir +/+ "Mapper";
case
{ File.type(link) == \symlink } {
	File.delete(link);
	("Removed %."
		"\n  Recompile the class library to finish.".format(link)).postln;
}
{ File.type(link) == \not_found } {
	"Nothing to remove at %.".format(link).postln;
}
{ true } {
	"% is not a link, leaving it alone.".format(link).warn;
};
)
