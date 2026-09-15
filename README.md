# svgo-kt

A pure-Kotlin reimplementation of [SVGO](https://github.com/svg/svgo) 4.1.0 — optimize/minify SVG
without a JavaScript runtime.

- **Zero dependencies.** No XML parser, no CSS library, no JSON library. The SVG parser, the CSS
  tokenizer, the CSS selector engine and even JavaScript's number formatting are implemented from
  scratch.
- **Kotlin Multiplatform.** All library code lives in `commonMain` and uses nothing but the Kotlin
  standard library, so it builds for JVM, JS, Native (Windows/Linux/macOS/iOS) and anything else
  Kotlin targets.
- **Faithful.** All 53 built-in plugins plus `preset-default` are implemented, and the port is
  verified against upstream's own fixture suite — all 377 cases match the JavaScript original
  byte-for-byte. The CSS minifier is additionally diffed against `csso`, the minifier SVGO
  delegates to, over hand-written and randomly generated stylesheets.
- **Ships a CLI.** A single self-contained `svgo` executable per desktop platform — 3.6 MB, no JVM,
  no Node — plus a runnable jar for the JVM.

## Installation

```kotlin
dependencies {
    implementation("io.github.tobsef:svgo-kt:4.1.0")
}
```

### Publishing to your local Maven repository

```bash
gradle publishToMavenLocal
gradle reportPublishedTargets   # which targets this host can actually produce
```

Then in the consuming project:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.tobsef:svgo-kt:4.1.0")
        }
    }
}
```

The dependency belongs in `commonMain` -- the whole API is common code, so shared UI code can call
`optimize` directly and every target gets its own artifact.

The JVM, `js`, and `wasmJs` (Kotlin/Wasm for the browser and Node.js) variants are pure Kotlin and
publish from any host; only the Kotlin/Native artifacts are host-dependent.

**Which targets you get depends on the machine you publish from.** Kotlin/Native cross-compiles only
within the host's platform family, so a Windows host produces `jvm`, `js`, `wasmJs`, `mingwX64` and
`linuxX64`, while the Apple targets need a macOS host. By default the build declares *only* the targets the host
can build, so the published Gradle metadata never advertises a variant whose module is missing --
otherwise a consumer with an iOS target fails with a confusing "could not find
`svgo-kt-iosarm64`". Run `reportPublishedTargets` to see the split.

For a real release, publish from each OS into the same repository with the full target list
declared:

```bash
gradle publish -PhostTargetsOnly=false
```

An `androidTarget()` consumer resolves the `jvm` variant, since Kotlin treats `androidJvm` as
compatible with `jvm`. If you need a dedicated Android variant, add the target to `build.gradle.kts`
and republish -- the library code itself needs no changes, because it is already platform
independent.

## Usage

```kotlin
import io.github.tobsef.svgo.optimize

val result = optimize("""<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10"/></svg>""")
println(result.data)
// <svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0h10v10H0z"/></svg>
```

With no configuration, `optimize` runs `preset-default` — the same 34 plugins, in the same order, as
the JavaScript original.

### Configuration

```kotlin
import io.github.tobsef.svgo.*

val result = optimize(
    svg,
    Config(
        plugins = listOf(
            PluginConfig(
                "preset-default",
                Params.of(
                    "overrides" to mapOf(
                        // turn a preset plugin off
                        "removeViewBox" to false,
                        // or reconfigure it
                        "removeDesc" to mapOf("removeAny" to true),
                    ),
                ),
            ),
            // plugins outside the preset are added explicitly
            PluginConfig("removeDimensions"),
            PluginConfig("prefixIds", Params.of("prefix" to "icon")),
        ),
        multipass = true,
        floatPrecision = 2,
        js2svg = StringifyOptions(pretty = true, indent = 2),
    ),
)
```

`Config` mirrors SVGO's JavaScript config object:

| Option | Type | Meaning |
| --- | --- | --- |
| `plugins` | `List<PluginConfig>?` | `null` uses `preset-default`; an empty list runs no plugins |
| `multipass` | `Boolean` | re-run the pipeline while the output keeps shrinking (max 10 passes) |
| `floatPrecision` | `Int?` | global override applied to every plugin that takes a precision |
| `path` | `String?` | source path, used by `prefixIds` to derive its prefix |
| `js2svg` | `StringifyOptions?` | serialization: `pretty`, `indent`, `useShortTags`, `eol`, `finalNewline` |
| `datauri` | `String?` | wrap the output as `"base64"`, `"enc"` or `"unenc"` data URI |

Plugin parameters are untyped (`Params` wraps a `Map<String, Any?>`) because upstream options range
over booleans, numbers, strings, lists and nested objects. `Params` reproduces JavaScript's
defaulting rules: a default applies only when a key is *absent*.

### Working with the AST directly

```kotlin
val ast: Root = parseSvg(svg)

visit(ast, Visitor(
    element = Callbacks(enter = { node, parent ->
        if (node.name == "title") detachNodeFromParent(node, parent)
    }),
))

val output: String = stringifySvg(ast)
```

A custom plugin is just a `PluginFn` — `(Root, Params, PluginInfo) -> Visitor?`:

```kotlin
val stripTitles = PluginConfig(
    name = "stripTitles",
    fn = { _, _, _ ->
        Visitor(element = Callbacks(enter = { node, parent ->
            if (node.name == "title") detachNodeFromParent(node, parent)
        }))
    },
)
optimize(svg, Config(plugins = listOf(PluginConfig("preset-default"), stripTitles)))
```

Returning `VISIT_SKIP` from an `enter` callback skips that node's children.

## Command line

The CLI is built from the same `commonMain` code on every host: the shared implementation talks to a
`CliIo` interface, and the JVM and Kotlin/Native hosts supply file access. Both produce identical
bytes.

### Building it

```bash
# a standalone executable -- no JVM, no Node, only system libraries
gradle linkSvgoReleaseExecutableMingwX64    # build/bin/mingwX64/svgoReleaseExecutable/svgo.exe
gradle linkSvgoReleaseExecutableLinuxX64    # build/bin/linuxX64/svgoReleaseExecutable/svgo.kexe
gradle linkSvgoReleaseExecutableMacosArm64  # build/bin/macosArm64/svgoReleaseExecutable/svgo.kexe

# a runnable jar (library + CLI + kotlin-stdlib)
gradle jvmCliJar                            # build/libs/svgo-kt-cli-4.1.0.jar
```

Kotlin/Native cross-compiles only to the host platform family, so build each executable on (or for)
its own OS.

### Using it

```bash
svgo icon.svg                       # optimize in place
svgo icon.svg -o small.svg          # write elsewhere
svgo a.svg b.svg -o out/            # several files into a folder
svgo -f assets -r -o dist           # a whole folder tree
cat icon.svg | svgo -i - -o -       # stdin to stdout
svgo -s '<svg .../>' -o -           # optimize a literal string

java -jar svgo-kt-cli-4.1.0.jar icon.svg -o -   # same CLI on the JVM
```

| Option | Meaning |
| --- | --- |
| `-i, --input <FILE...>` | Input files, `-` for stdin (also accepted as positional arguments) |
| `-s, --string <STRING>` | Input SVG data string |
| `-f, --folder <FOLDER>` | Input folder, optimize and rewrite all `*.svg` files |
| `-r, --recursive` | Use with `--folder`, recurse into subfolders |
| `--exclude <REGEX...>` | Use with `--folder`, skip files whose name matches |
| `-o, --output <FILE...>` | Output file or folder (default: overwrite the input), `-` for stdout |
| `--datauri <FORMAT>` | Output as a data URI: `base64`, `enc` or `unenc` |
| `-p, --precision <INT>` | Digits in the fractional part, overrides plugin params |
| `--multipass` | Pass over SVGs repeatedly until nothing more is gained |
| `--disable <PLUGIN...>` | Disable a `preset-default` plugin |
| `--enable <PLUGIN...>` | Enable a plugin that is not part of `preset-default` |
| `--pretty`, `--indent <INT>` | Pretty-print, with the given indent width |
| `--eol <EOL>` | Line break: `lf` or `crlf` (default: platform native) |
| `--final-newline` | Ensure the output ends with a line break |
| `-q, --quiet` | Only output error messages |
| `--show-plugins` | List available plugins and exit |
| `-v, --version`, `-h, --help` | |

Unlike upstream there is no `--config`: a JavaScript config file would need a JavaScript engine.
Configure the preset with `--disable`/`--enable`, or use the library API for anything finer.

## Plugins

All 53 built-in plugins are implemented. `getPlugin(name)` resolves one by name,
`ALL_PLUGIN_NAMES` lists them, and `builtinPlugins()` returns them together with `preset-default`.

**In `preset-default`, in execution order:** `removeDoctype`, `removeXMLProcInst`, `removeComments`,
`removeDeprecatedAttrs`, `removeMetadata`, `removeEditorsNSData`, `cleanupAttrs`, `mergeStyles`,
`inlineStyles`, `minifyStyles`, `cleanupIds`, `removeUselessDefs`, `cleanupNumericValues`,
`convertColors`, `removeUnknownsAndDefaults`, `removeNonInheritableGroupAttrs`,
`removeUselessStrokeAndFill`, `cleanupEnableBackground`, `removeHiddenElems`, `removeEmptyText`,
`convertShapeToPath`, `convertEllipseToCircle`, `moveElemsAttrsToGroup`, `moveGroupAttrsToElems`,
`collapseGroups`, `convertPathData`, `convertTransform`, `removeEmptyAttrs`,
`removeEmptyContainers`, `mergePaths`, `removeUnusedNS`, `sortAttrs`, `sortDefsChildren`,
`removeDesc`.

**Opt-in (not in the preset):** `addAttributesToSVGElement`, `addClassesToSVGElement`,
`cleanupListOfValues`, `convertOneStopGradients`, `convertStyleToAttrs`, `prefixIds`,
`removeAttributesBySelector`, `removeAttrs`, `removeDimensions`, `removeElementsByAttr`,
`removeOffCanvasPaths`, `removeRasterImages`, `removeScripts` (alias: `removeScriptElement`),
`removeStyleElement`, `removeTitle`, `removeViewBox`, `removeXMLNS`, `removeXlink`, `reusePaths`.

Parameter names and defaults are identical to upstream, so the
[SVGO plugin documentation](https://svgo.dev/docs/plugins/) applies verbatim.

Two parameters that are functions in JavaScript take functional interfaces here:
`prefixIds`' `prefix` accepts a `PrefixGenerator`, and `addClassesToSVGElement`'s `classNames`
entries accept a `ClassNameGenerator`.

## Fidelity

The port is verified five ways:

1. **Upstream fixture suite** — all 377 `test/plugins/*.svg.txt` fixtures from SVGO run through
   `optimize` and compared byte-for-byte, each applied twice to also assert idempotence.
   **All 377 match exactly**; there are no known gaps.
2. **Unit tests** ported from `lib/path.test.js`, `lib/parser.test.js` and `lib/xast.test.js`, plus
   an ECMA-262 number-formatting suite.
3. **Whole-document tests** — complete real-world SVGs optimized with the default preset and
   `multipass`, compared against output captured from the reference JavaScript SVGO 4.1.0. All match
   byte-for-byte.
4. **CSS differential suite** — the CSS minifier is compared against `csso` itself, the minifier
   SVGO delegates to. 294 hand-written cases cover one behaviour each of csso's clean, replace and
   restructure stages; 1,500 pseudo-random stylesheets, generated from the vocabulary those passes
   branch on, cover the combinations nobody thought to write down. Runs of 20,000–30,000 sheets
   across three seeds were used while porting — see [`tools/`](tools/README.md) to repeat them.
5. **Corpus cross-check** — every fixture SVG run through the full `preset-default` with
   `multipass`, diffed against the JavaScript original: 374 of 377 identical. Of the remaining
   three, two are inputs where the JavaScript original throws and this implementation does not; the
   third is described below.

Results: **2,236 tests, 0 failures** on the JVM. The 59 platform-independent tests also run green on
Kotlin/JS, which is what makes the multiplatform claim more than aspirational — including the
number formatting, where JVM and JS agree digit for digit.

### The CSS engine

SVGO delegates CSS work to [`csso`](https://github.com/css/csso) and
[`css-tree`](https://github.com/csstree/csstree). This library ships its own CSS engine, and it
reproduces all three of csso's stages:

- **clean** — comments, empty rules, unreachable at-rules, and rules whose selectors can no longer
  match any element in the document;
- **replace** — value minification: colour keywords and `rgb()`/`hsl()` functions to the shortest
  equivalent, hex shortening, number packing, zero lengths losing their unit, and the `font`,
  `font-weight`, `background`, `border` and `outline` shorthand rewrites;
- **restructure** — the eight passes of
  [`csso/lib/restructure`](https://github.com/css/csso/tree/master/lib/restructure): at-rule
  relocation and `@media` merging, rule merging by selector and by declaration block, selector-list
  disjoining, shorthand merging (`padding-top/right/bottom/left` → `padding`), removal of
  declarations a later one overrides or a shorthand covers, and extraction of shared declarations
  into their own rule where that comes out smaller.

Generation reproduces css-tree's *safe* token adjacency, so a separating space appears exactly where
re-parsing would otherwise read a different token stream.

### Known differences from JavaScript SVGO

Two of csso's decisions consult css-tree's full syntax database: whether a keyword sits in a
`<color>` position, and whether `0%` may be written as `0`. Shipping that database would roughly
double the artifact, so both questions are answered ahead of time from tables derived from that
same lexer — 56 properties that take a colour keyword, 175 where a zero percentage is a length,
plus the function positions (gradients for colours, `translate()`/`inset()`/`polygon()` and friends
for lengths) and a term count that stands in for the `<shadow>` grammar. Every property and function
csso's own lexer knows is covered; a property invented after this table was generated would simply
not be minified, never mis-minified.

One fixture, `minifyStyles.03`, differs in the full pipeline: SVGO's `cleanupEnableBackground`
re-serializes the whole `style` attribute with plain css-tree, which always escapes a `url()` rather
than quoting it. This implementation keeps the shorter quoted form, so its output is four bytes
smaller and otherwise identical.

Everything outside CSS minification — path data, transforms, colors, numbers, structure, attributes
— matches the reference exactly.

## Building

Requires JDK 17+.

```bash
gradle jvmTest          # the full suite (fixtures, unit tests, real-world SVGs, CLI)
gradle jsNodeTest       # the platform-independent subset, on Kotlin/JS
gradle build            # build and test every target, including the CLI artifacts
```

See [Command line](#command-line) for the executable and jar tasks, and
[Installation](#publishing-to-your-local-maven-repository) for publishing.

To dump the full-preset output of every fixture SVG for cross-checking against another
implementation:

```bash
gradle jvmTest -Dsvgo.corpusDump=/tmp/kt-out
```

To regenerate the CSS parity corpora from the reference `csso`, see [`tools/`](tools/README.md).

## Project layout

| Path | Contents |
| --- | --- |
| `Xast.kt`, `Visit.kt` | node model and visitor engine (`lib/types.ts`, `lib/util/visit.js`) |
| `Parser.kt`, `Stringifier.kt` | SVG ⇄ AST (`lib/parser.js`, `lib/stringifier.js`) |
| `JsNumber.kt` | exact ECMA-262 `Number::toString` / `toFixed`, via a small big-decimal helper |
| `Path.kt`, `PathUtil.kt` | path-data parsing/serialization and geometry (`lib/path.js`, `plugins/_path.js`) |
| `Transforms.kt` | transform matrices and decomposition (`plugins/_transforms.js`) |
| `css/CssSyntax.kt` | CSS Syntax Level 3 tokenizer and parser (replaces `tinycss2`/`css-tree`) |
| `css/Selector.kt` | CSS selector parser and specificity (replaces `cssselect`/`css-what`) |
| `Css.kt`, `Style.kt` | selector matching and computed styles (`lib/style.js`) |
| `CssMinify.kt`, `CssReplace.kt`, `css/Restructure.kt` | the CSS minifier: csso's clean, replace and restructure stages |
| `Collections.kt` | the SVG element/attribute/color tables, generated from `plugins/_collections.js` |
| `plugins/` | all 53 plugins |
| `Engine.kt`, `Builtin.kt`, `Optimize.kt` | plugin engine, registry and the public `optimize` entry point |
| `cli/Cli.kt` | the command line, platform-independent behind a `CliIo` interface |
| `jvmMain/`, `nativeMain/` | the two CLI hosts: `java.io` and `fopen`/`opendir` respectively |
| `tools/` | scripts that record `csso` output for the CSS differential suite |

Why JavaScript number semantics need 250 lines: SVGO's output is compared byte-for-byte, and
`Number.prototype.toString` has behaviour no platform reproduces for free — exponent thresholds at
`1e-6`/`1e21`, the `1e-7` (not `1e-07`) spelling, shortest-round-trip digit selection, and
`toFixed`'s "round the *exact* binary value, ties away from zero" rule that makes
`(1.005).toFixed(2) === "1.00"`. `JsNumber.kt` derives all of it from the exact decimal expansion of
the IEEE-754 double, so results are identical on every Kotlin target.

## Credits

- [SVGO](https://github.com/svg/svgo) by Kir Belevich and contributors — the original.
- [svgo-py](https://github.com/TobseF/svgo-py) — the Python port this implementation was derived
  from.

## License

MIT
