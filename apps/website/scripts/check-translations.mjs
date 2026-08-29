#!/usr/bin/env node
// Checks the Spanish and Catalan copies of the site against the English pages.
//
//   node apps/website/scripts/check-translations.mjs
//
// For each translated page it verifies that
//   (a) every id in the English page is present in the translation (anchors keep working),
//   (b) no English sentence longer than six words survives outside <code>, <pre>, <script> and
//       <style> — text in alt/title/aria-label/placeholder/meta content included,
//   (c) every relative href/src resolves to a file that exists (Vercel's cleanUrls in mind:
//       /docs is docs/index.html), and a fragment names an id on the page it points at,
//   (d) the <style> block is byte-identical to the English one, and the <script> blocks differ
//       only in lines that carry user-visible strings — which are printed, so nothing else can
//       slip in.
// Exits 1 if anything fails. Pure node, no dependencies — the site has no build step.
import { readFileSync, existsSync, statSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SITE = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PAGES = [
  { en: 'index.html', translations: ['es/index.html', 'ca/index.html'] },
  { en: 'docs/index.html', translations: ['es/docs/index.html', 'ca/docs/index.html'] },
];

// Words that are English and not Spanish or Catalan. "a", "no", "en", "on" (Catalan for "where"),
// "me", "so" and the like are left out on purpose: they would flag every other sentence.
const ENGLISH = new Set(('the and of is it that this with your you are from which what when not or by for an its as into than ' +
  'then there here where will can does do has have be but if at to was were been being have had they them their his her ' +
  'we our ours my mine yours these those any some every each all most more than much many much only just still yet also ' +
  'because before after while until since through during without within about against between among over under above ' +
  'below across along around behind beside beyond inside outside toward towards upon out off up down again further ' +
  'once twice already never always often sometimes rarely ever never nothing something anything everything nobody ' +
  'somebody anybody everybody none one two three four five six seven eight nine ten first second third last next other ' +
  'another such same very too quite rather almost enough both either neither whether though although even also instead ' +
  'itself yourself himself herself themselves ourselves myself who whom whose why how would should could might may must ' +
  'shall need needs needed want wants wanted make makes made get gets got give gives given take takes taken keep keeps kept ' +
  'let lets put puts run runs running read reads reading write writes written says said tell tells told ask asks asked').split(/\s+/));

function stripBlocks(html) {
  return html
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<pre[\s\S]*?<\/pre>/gi, ' ')
    .replace(/<code[\s\S]*?<\/code>/gi, ' ');
}
function decode(s) {
  return s.replace(/&amp;/g, '&').replace(/&rsquo;/g, '’').replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}
// Human-readable text: element text plus the attributes that people read.
function visibleText(html) {
  const body = stripBlocks(html);
  const attrs = [];
  body.replace(/\s(?:alt|title|aria-label|placeholder|content)="([^"]*)"/g, (_, v) => { attrs.push(v); return ''; });
  const text = body.replace(/<[^>]+>/g, '\n');
  return decode(text + '\n' + attrs.join('\n'));
}
function englishLeftovers(html) {
  const out = [];
  for (const raw of visibleText(html).split(/[\n.!?…]+/)) {
    const s = raw.replace(/\s+/g, ' ').trim();
    const words = s.match(/[A-Za-zÀ-ÿĀ-ſ'’]+/g) || [];
    if (words.length <= 6) continue;
    // A SQL query shown as a node subtitle is code, whichever tag it sits in.
    if (/^select\b[\s\S]*\bfrom\b/i.test(s)) continue;
    const hits = words.filter((w) => ENGLISH.has(w.toLowerCase())).length;
    // Two unambiguous English words in a sentence of seven or more is not Spanish or Catalan.
    if (hits >= 2) out.push(s);
  }
  return out;
}

function ids(html) {
  const set = new Set();
  html.replace(/\sid="([^"]+)"/g, (_, id) => { set.add(id); return ''; });
  return set;
}

// A root-relative or relative URL resolves the way Vercel serves it with cleanUrls: the path
// itself, the path with .html, or the path as a folder with an index.html.
function resolveTarget(fromFile, url) {
  const path = url.startsWith('/') ? join(SITE, url) : resolve(dirname(fromFile), url);
  for (const candidate of [path, path + '.html', join(path, 'index.html')]) {
    if (existsSync(candidate) && statSync(candidate).isFile()) return candidate;
  }
  return null;
}
function brokenLinks(file, html) {
  const out = [];
  const pageIds = ids(html);
  const re = /\s(?:href|src)="([^"]*)"/g;
  let m;
  const outsideCode = stripBlocks(html).replace(/<pre[\s\S]*?<\/pre>/gi, '');
  while ((m = re.exec(outsideCode))) {
    const url = m[1];
    if (url === '#' || /^(https?:|mailto:|data:|javascript:)/.test(url)) continue;
    const [path, frag] = url.split('#');
    if (path === '') {
      if (frag && !pageIds.has(frag)) out.push(`${url} — no id "${frag}" on this page`);
      continue;
    }
    const target = resolveTarget(file, path);
    if (!target) { out.push(`${url} — no file for ${path}`); continue; }
    if (frag && !ids(readFileSync(target, 'utf8')).has(frag)) out.push(`${url} — no id "${frag}" in ${target.slice(SITE.length + 1)}`);
  }
  return out;
}

function block(html, tag) {
  const re = new RegExp(`<${tag}[^>]*>[\\s\\S]*?<\\/${tag}>`, 'gi');
  return (html.match(re) || []).join('\n');
}
function scriptDiff(en, tr) {
  const a = block(en, 'script').split('\n');
  const b = block(tr, 'script').split('\n');
  const out = [];
  if (a.length !== b.length) return [`script block has ${b.length} lines, English has ${a.length}`];
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) out.push(`- ${a[i].trim()}\n      + ${b[i].trim()}`);
  return out;
}

let failed = false;
function report(title, items, { fatal = true } = {}) {
  if (!items.length) { console.log(`  ok   ${title}`); return; }
  console.log(`  ${fatal ? 'FAIL' : 'note'} ${title} (${items.length})`);
  for (const i of items) console.log('       ' + i);
  if (fatal) failed = true;
}

for (const page of PAGES) {
  const enFile = join(SITE, page.en);
  const en = readFileSync(enFile, 'utf8');
  const enIds = ids(en);
  console.log(`\n${page.en}`);
  report('relative links resolve', brokenLinks(enFile, en));
  for (const rel of page.translations) {
    const file = join(SITE, rel);
    console.log(`\n${rel}`);
    if (!existsSync(file)) { report('exists', ['missing']); continue; }
    const tr = readFileSync(file, 'utf8');
    const trIds = ids(tr);
    report('(a) every English id is present', [...enIds].filter((id) => !trIds.has(id)).map((id) => `missing id "${id}"`));
    report('(b) no English sentence longer than 6 words outside code/pre/script/style', englishLeftovers(tr));
    report('(c) relative links resolve', brokenLinks(file, tr));
    report('(d) <style> byte-identical to English', block(en, 'style') === block(tr, 'style') ? [] : ['differs']);
    const lang = rel.slice(0, 2);
    report(`(d) <html lang="${lang}">`, tr.startsWith(`<!doctype html>\n<html lang="${lang}">`) ? [] : ['wrong or missing lang attribute']);
    report('(d) <script> differs from English only in these lines', scriptDiff(en, tr), { fatal: false });
  }
}

console.log(failed ? '\nFAILED' : '\nall checks passed');
process.exit(failed ? 1 : 0);
