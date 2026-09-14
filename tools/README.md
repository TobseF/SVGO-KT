# CSS parity tooling

The CSS minifier in `io.github.tobsef.svgo.CssMinify` is a port of [csso], the minifier the
reference SVGO uses. These scripts record what csso produces so the Kotlin port can be diffed
against it offline; `CssMinifyTest` replays the recordings.

They pull in the same `csso` release SVGO 4.1.0 depends on:

```bash
cd tools
npm install
npm run corpus   # hand-written cases -> ../src/jvmTest/resources/css/minify.txt
npm run fuzz     # 1500 random sheets -> ../src/jvmTest/resources/css/fuzz.txt
```

Both commands overwrite the committed corpora in place and reproduce them byte for byte, so a
clean checkout plus `npm install` is enough to confirm a corpus was not hand-edited.

`node css-fuzz.mjs <out> [count] [seed]` builds pseudo-random stylesheets from the selector,
property and value vocabulary the restructuring passes branch on. The committed corpus is 1500
sheets; runs of 20k-30k with other seeds were used while porting and are worth repeating after
changes to `CssReplace.kt` or `css/Restructure.kt`:

```bash
node css-fuzz.mjs ../src/jvmTest/resources/css/fuzz.txt 25000 913001
cd .. && gradle jvmTest --tests '*CssMinifyTest'
git checkout src/jvmTest/resources/css/fuzz.txt   # put the committed corpus back
```

`css-cases.mjs` and `css-cases2.mjs` hold the hand-written cases -- one per documented behaviour of
csso's clean, replace and restructure stages. Add to them when you fix a parity bug.

[csso]: https://github.com/css/csso
