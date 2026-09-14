import * as csso from 'csso';
import { cases } from './css-cases.mjs';
import { cases2 } from './css-cases2.mjs';
import fs from 'node:fs';

const blockCases = [
  "stroke-width:3; margin-top: 1em; margin-right: 1em; margin-bottom: 1em; margin-left: 1em;",
  "fill:red;fill:blue",
  "margin:1px;margin-top:2px",
  "margin-top:1px;margin-right:2px;margin-bottom:1px;margin-left:2px",
  "color:red!important;color:blue",
  "padding-top:1px;padding-right:1px;padding-bottom:1px;padding-left:1px",
  "font:12px serif;font-size:14px",
  "display:block;display:table",
  "COLOR:RED;--X:Y",
  "background:url( 'a.png' )",
  "stroke-width:0.5;opacity:.50",
  "fill:rgb(255,0,0)",
  "width:0px;height:0em",
  "font-weight:normal",
  "border:medium none",
  "transform:translate( 1px , 2px )",
  "",
  "   ",
  "color:red;",
];

const out = [];
for (const css of [...cases, ...cases2]) {
  let r;
  try { r = csso.minify(css, { restructure: true }).css; } catch (e) { continue; }
  out.push('@@@SHEET\n' + css + '\n@@@EXPECT\n' + r);
}
for (const css of blockCases) {
  let r;
  try { r = csso.minifyBlock(css, { restructure: true }).css; } catch (e) { continue; }
  out.push('@@@BLOCK\n' + css + '\n@@@EXPECT\n' + r);
}
fs.writeFileSync('../src/jvmTest/resources/css/minify.txt', out.join('\n@@@END\n') + '\n@@@END\n');
console.log('records:', out.length);
