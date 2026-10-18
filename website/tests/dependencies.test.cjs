const assert = require('node:assert/strict');
const {getEventListeners} = require('node:events');
const fs = require('node:fs');
const Module = require('node:module');
const path = require('node:path');
const {test} = require('node:test');
const {onRouteDidUpdate} = require('../src/client/dependencies.mjs');

// The CI job has no npm install. Only the browser DOM boundary is replaced;
// Node's native EventTarget owns dispatch and listener removal in these tests.
class Element extends EventTarget {
  constructor(tagName, className = '') {
    super();
    this.tagName = tagName;
    this.className = className;
    this.children = [];
    this.parentNode = null;
    this.attributes = new Map();
    this.open = false;
  }

  append(...children) {
    for (const child of children) {
      child.remove();
      child.parentNode = this;
      this.children.push(child);
    }
  }

  before(child) {
    child.remove();
    child.parentNode = this.parentNode;
    this.parentNode.children.splice(this.parentNode.children.indexOf(this), 0, child);
  }

  remove() {
    if (this.parentNode) {
      this.parentNode.children.splice(this.parentNode.children.indexOf(this), 1);
      this.parentNode = null;
    }
  }

  setAttribute(name, value) { this.attributes.set(name, value); }

  querySelectorAll(selector) {
    return this.children.flatMap(child => [
      ...(selector.startsWith('.')
        ? child.className.split(/\s+/).includes(selector.slice(1))
        : child.tagName === selector) ? [child] : [],
      ...child.querySelectorAll(selector),
    ]);
  }

  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

class Document extends Element {
  constructor(...children) {
    super('document');
    this.append(...children);
  }

  createElement(tagName) { return new Element(tagName); }
}

function inventory(...states) {
  const section = new Element('section', 'dependency-inventory');
  const heading = new Element('h2');
  const table = new Element('table', 'dependency-table');
  for (const open of states) {
    const body = new Element('tbody', 'dependency-entry');
    const row = new Element('tr', 'dependency-details-row');
    const cell = new Element('td');
    const details = new Element('details', 'dependency-details');
    details.open = open;
    details.append(new Element('summary', 'dependency-summary'), new Element('div', 'dependency-metadata'));
    cell.append(details);
    row.append(cell);
    body.append(row);
    table.append(body);
  }
  section.append(heading, table);
  return section;
}

function browser(t, ...groups) {
  global.document = new Document(...groups);
  global.window = new EventTarget();
  t.after(() => {
    global.document = new Document();
    onRouteDidUpdate();
    delete global.document;
    delete global.window;
  });
}

const states = group => group.querySelectorAll('.dependency-details').map(detail => detail.open);
const click = button => button.dispatchEvent(new Event('click'));

test('bulk controls affect only their inventory and preserve the static content', t => {
  const runtime = inventory(false, true);
  const tests = inventory(false);
  const unrelated = new Element('details', 'other-details');
  browser(t, runtime, tests, unrelated);
  const original = runtime.querySelectorAll('.dependency-details');
  onRouteDidUpdate();
  assert.deepEqual(runtime.children.map(child => child.tagName), ['h2', 'div', 'table']);
  const controls = runtime.querySelector('.dependency-controls');
  assert.equal(controls.attributes.get('role'), 'group');
  assert.deepEqual(controls.children.map(button => [button.type, button.textContent]), [
    ['button', 'Expand all'], ['button', 'Collapse all'],
  ]);
  click(controls.children[0]);
  assert.deepEqual(states(runtime), [true, true]);
  assert.deepEqual(states(tests), [false]);
  assert.equal(unrelated.open, false);
  click(controls.children[1]);
  assert.deepEqual(states(runtime), [false, false]);
  assert.deepEqual(runtime.querySelectorAll('.dependency-details'), original);
});

test('repeated route hooks clean controls and listeners without resetting individual disclosures', t => {
  const group = inventory(false, true);
  browser(t, group);
  onRouteDidUpdate();
  const oldControls = group.querySelector('.dependency-controls');
  const oldExpand = oldControls.children[0];
  group.querySelectorAll('.dependency-details')[0].open = true;
  onRouteDidUpdate();
  assert.equal(group.querySelectorAll('.dependency-controls').length, 1);
  assert.equal(oldControls.parentNode, null);
  assert.equal(getEventListeners(oldExpand, 'click').length, 0);
  assert.equal(getEventListeners(window, 'beforeprint').length, 1);
  assert.equal(getEventListeners(window, 'afterprint').length, 1);
  assert.deepEqual(states(group), [true, true]);
  group.querySelectorAll('.dependency-details')[0].open = false;
  click(oldExpand);
  assert.deepEqual(states(group), [false, true]);
});

test('printing expands every inventory and restores mixed states even after repeated beforeprint events', t => {
  const runtime = inventory(false, true);
  const tests = inventory(false);
  browser(t, runtime, tests);
  onRouteDidUpdate();
  window.dispatchEvent(new Event('afterprint'));
  assert.deepEqual(states(runtime), [false, true]);
  window.dispatchEvent(new Event('beforeprint'));
  window.dispatchEvent(new Event('beforeprint'));
  assert.deepEqual(states(runtime), [true, true]);
  assert.deepEqual(states(tests), [true]);
  window.dispatchEvent(new Event('afterprint'));
  assert.deepEqual(states(runtime), [false, true]);
  assert.deepEqual(states(tests), [false]);
  runtime.querySelectorAll('.dependency-details')[0].open = true;
  window.dispatchEvent(new Event('beforeprint'));
  window.dispatchEvent(new Event('afterprint'));
  assert.deepEqual(states(runtime), [true, true]);
});

test('leaving during print restores old state, removes listeners, and supports a later return', t => {
  const group = inventory(false, true);
  browser(t, group);
  onRouteDidUpdate();
  window.dispatchEvent(new Event('beforeprint'));
  document = new Document(new Element('article'));
  onRouteDidUpdate();
  assert.deepEqual(states(group), [false, true]);
  assert.equal(group.querySelectorAll('.dependency-controls').length, 0);
  assert.equal(getEventListeners(window, 'beforeprint').length, 0);
  assert.equal(getEventListeners(window, 'afterprint').length, 0);
  window.dispatchEvent(new Event('beforeprint'));
  assert.deepEqual(states(group), [false, true]);
  document = new Document(group);
  onRouteDidUpdate();
  click(group.querySelector('.dependency-controls').children[0]);
  assert.deepEqual(states(group), [true, true]);
});

test('pages without dependency disclosures receive no controls or print listeners', t => {
  const empty = inventory();
  const unrelated = new Element('details', 'dependency-details');
  browser(t, empty, unrelated);
  onRouteDidUpdate();
  assert.equal(document.querySelectorAll('.dependency-controls').length, 0);
  assert.equal(getEventListeners(window, 'beforeprint').length, 0);
  assert.equal(getEventListeners(window, 'afterprint').length, 0);
});

test('the theme adapter keeps dependency details native and delegates every other disclosure unchanged', () => {
  const filename = path.join(__dirname, '../src/theme/MDXComponents/Details/index.js');
  const adapter = new Module(filename);
  const original = function OriginalDetails() {};
  adapter.require = name => {
    if (name === 'react') return {createElement: (type, props) => ({type, props})};
    assert.equal(name, '@theme-original/MDXComponents/Details');
    return {default: original};
  };
  adapter._compile(fs.readFileSync(filename, 'utf8'), filename);
  for (const props of [
    {className: 'dependency-details', children: ['summary', 'metadata'], open: true},
    {className: 'other dependency-details extra', id: 'coordinate-id'},
  ]) assert.deepEqual(adapter.exports(props), {type: 'details', props});
  for (const props of [{}, {className: 'other-details'}, {className: 'dependency-details-extra'}]) {
    assert.deepEqual(adapter.exports(props), {type: original, props});
  }
});
