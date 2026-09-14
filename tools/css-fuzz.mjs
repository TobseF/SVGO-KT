import * as csso from 'csso';
import fs from 'node:fs';

let seed = parseInt(process.argv[4] || '12345', 10);
const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
const pick = (a) => a[Math.floor(rnd() * a.length)];

const selectors = ['.a', '.b', '.c', 'div', 'p', 'span', '#id', '.a .b', '.a>.b', '.a+.b', 'a:hover', 'a::after',
  '*', '.a[x=y]', 'p.a', '.a~.b', 'a:focus', '.a:not(.b)', 'a::before', ':root', '.a.b', 'div#id.a', '[hidden]',
  'a:nth-child(2n+1)', 'svg|rect', '.a *', 'p span em'];
const props = ['color', 'fill', 'stroke', 'margin', 'margin-top', 'margin-right', 'margin-bottom', 'margin-left',
  'padding', 'padding-top', 'padding-right', 'padding-bottom', 'padding-left', 'border', 'border-width',
  'border-top-width', 'border-right-width', 'border-bottom-width', 'border-left-width', 'display', 'position',
  'font', 'font-weight', 'font-size', 'background', 'opacity', 'width', 'height', 'top', 'left', 'overflow', 'cursor',
  'border-color', 'border-style', 'border-top-color', 'border-right-color', 'border-bottom-color', 'border-left-color',
  'border-top-style', 'border-right-style', 'border-bottom-style', 'border-left-style', 'border-top', 'border-left',
  'list-style', 'list-style-type', 'list-style-position', 'font-style', 'font-variant', 'font-family',
  'text-align', 'src', 'outline', 'background-color', 'background-image', 'stroke-width', 'stroke-dasharray',
  '-webkit-box-shadow', 'box-shadow', '--custom', 'text-decoration', 'transform', 'transition'];
const values = ['red', 'blue', '#fff', '#ff0000', 'rgb(1,2,3)', 'rgba(0,0,0,.5)', '0', '0px', '1px', '2px', '1em',
  '1rem', '1vw', 'none', 'block', 'inline', 'table', 'absolute', 'fixed', 'bold', 'normal', 'auto', 'hidden',
  'calc(1px + 2px)', 'var(--x)', '12px/1.2 Arial', 'inherit', '1px solid red', '.5', 'medium none', 'pointer',
  'solid', 'dashed', 'initial', 'unset', 'revert', 'transparent', 'currentColor', 'url(a.png)', 'url("a b.png")',
  '0 0 1px red', 'translate(1px,2px)', 'rect(0,0,0,0)', 'rect(0 0 0 0)', '100%', '0%', '1 1 0px', 'left', 'justify-all',
  'flex', 'grid', 'ruby', 'run-in', 'scroll', 'repeat', 'a b', '"x"', 'disc', 'italic', 'small-caps', '1e3px',
  '-1px', '+1px', '010.50px', 'linear-gradient(red,blue)', '2s ease-in-out', 'crosshair', 'not-allowed'];

function decl() {
  const imp = rnd() < 0.1 ? '!important' : '';
  return `${pick(props)}:${pick(values)}${imp}`;
}
function rule() {
  const n = 1 + Math.floor(rnd() * 3);
  const sel = Array.from({ length: n }, () => pick(selectors)).join(',');
  const d = 1 + Math.floor(rnd() * 5);
  return `${sel}{${Array.from({ length: d }, decl).join(';')}}`;
}
function sheet() {
  const n = 1 + Math.floor(rnd() * 6);
  let out = '';
  for (let i = 0; i < n; i++) {
    const r = rnd();
    if (r < 0.12) out += `@media screen{${rule()}${rnd() < 0.5 ? rule() : ''}}`;
    else if (r < 0.17) out += `@media (max-width:${100 + i}px){${rule()}}`;
    else if (r < 0.20) out += `@keyframes k${i}{from{opacity:0}50%{opacity:.5}to{opacity:1}}`;
    else if (r < 0.23) out += `@supports (display:grid){${rule()}${rule()}}`;
    else if (r < 0.25) out += `@font-face{font-family:f${i};src:url(a.woff)}`;
    else if (r < 0.27) out += `/*! keep ${i} */`;
    else out += rule();
  }
  return out;
}

const out = [];
for (let i = 0; i < parseInt(process.argv[3] || '1200', 10); i++) {
  const css = sheet();
  let r;
  try { r = csso.minify(css, { restructure: true }).css; } catch (e) { continue; }
  out.push('@@@SHEET\n' + css + '\n@@@EXPECT\n' + r);
}
fs.writeFileSync(process.argv[2], out.join('\n@@@END\n') + '\n@@@END\n');
console.log('records:', out.length);
